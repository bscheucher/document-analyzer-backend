package com.example.docanalyzer.service;

import com.example.docanalyzer.dto.AnalysisProgressEvent;
import com.example.docanalyzer.entity.AnalysisResult;
import com.example.docanalyzer.entity.Document;
import com.example.docanalyzer.repository.DocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAnalysisService {

    private final DocumentRepository documentRepository;
    private final DocumentPersistenceService persistence;
    private final StorageService storageService;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    // Holds active SSE emitters by document ID
    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    // ── Upload ───────────────────────────────────────────────────────────────

    public Document upload(MultipartFile file) throws IOException {
        String storagePath = storageService.store(file);
        Document.FileType fileType = detectFileType(file.getContentType());

        Document doc = new Document();
        doc.setFilename(file.getOriginalFilename());
        doc.setFileType(fileType);
        doc.setFileSize(file.getSize());
        doc.setStoragePath(storagePath);
        doc.setContentType(file.getContentType());
        doc.setStatus(Document.DocumentStatus.PENDING);

        return documentRepository.save(doc);
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    public boolean delete(UUID documentId) {
        String storagePath = persistence.deleteAndReturnPath(documentId);
        if (storagePath == null) return false;

        try {
            storageService.delete(storagePath);
        } catch (IOException e) {
            log.warn("Failed to delete stored file {} for document {}", storagePath, documentId, e);
        }
        return true;
    }

    // ── SSE subscription ─────────────────────────────────────────────────────

    public SseEmitter subscribe(UUID documentId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5 min timeout
        emitters.put(documentId, emitter);

        emitter.onCompletion(() -> emitters.remove(documentId));
        emitter.onTimeout(() -> emitters.remove(documentId));
        emitter.onError(e -> emitters.remove(documentId));

        // If document is already done, send final state immediately
        documentRepository.findByIdWithResult(documentId).ifPresent(doc -> {
            if (doc.getStatus() == Document.DocumentStatus.DONE
                    || doc.getStatus() == Document.DocumentStatus.FAILED) {
                sendEvent(documentId, new AnalysisProgressEvent(
                        doc.getStatus().name(), "Analysis already complete", 100));
                emitter.complete();
            }
        });

        return emitter;
    }

    // ── Analysis pipeline (runs async) ───────────────────────────────────────

    @Async("analysisExecutor")
    public void analyzeAsync(UUID documentId) {
        Document doc = persistence.loadWithResult(documentId);
        AnalysisResult result = new AnalysisResult();

        try {
            sendEvent(documentId, new AnalysisProgressEvent("EXTRACTING", "Extracting text...", 10));
            persistence.updateStatus(documentId, Document.DocumentStatus.EXTRACTING);

            String extractedText = extractText(doc);
            result.setExtractedText(extractedText);
            sendEvent(documentId, new AnalysisProgressEvent("EXTRACTING",
                    doc.getFileType() == Document.FileType.PDF
                            ? "Text extracted successfully."
                            : "Image ready for vision model.", 30));

            sendEvent(documentId, new AnalysisProgressEvent("ANALYZING", "Sending to AI model...", 50));
            persistence.updateStatus(documentId, Document.DocumentStatus.ANALYZING);

            String llmResponse = doc.getFileType() == Document.FileType.IMAGE
                    ? llmService.analyzeImage(
                            storageService.readBytes(doc.getStoragePath()),
                            doc.getContentType() != null ? doc.getContentType() : "image/jpeg")
                    : llmService.analyzeText(extractedText);

            result.setRawLlmResponse(llmResponse);
            parseAndApplyLlmResponse(llmResponse, result);

            persistence.completeAnalysis(documentId, result);
            sendEvent(documentId, new AnalysisProgressEvent("DONE", "Analysis complete!", 100));

        } catch (Exception e) {
            log.error("Analysis failed for document {}", documentId, e);
            persistence.failAnalysis(documentId, result, e.getMessage());
            sendEvent(documentId, new AnalysisProgressEvent("FAILED", "Analysis failed: " + e.getMessage(), 100));
        } finally {
            SseEmitter emitter = emitters.remove(documentId);
            if (emitter != null) emitter.complete();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractText(Document doc) throws IOException {
        if (doc.getFileType() == Document.FileType.PDF) {
            Path pdfPath = storageService.load(doc.getStoragePath());
            try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
                return new PDFTextStripper().getText(pdf);
            }
        }
        return "";
    }

    private void parseAndApplyLlmResponse(String raw, AnalysisResult result) {
        String jsonSlice = extractJsonObject(raw);
        if (jsonSlice == null) {
            log.warn("No JSON object found in LLM response, storing raw text.");
            result.setSummary(raw);
            return;
        }
        try {
            JsonNode json = objectMapper.readTree(jsonSlice);

            result.setSummary(json.path("summary").asText());
            result.setDocumentType(json.path("documentType").asText());

            JsonNode topics = json.path("keyTopics");
            if (topics.isArray()) {
                List<String> topicList = objectMapper.convertValue(
                        topics, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                result.setKeyTopics(topicList);
            }
        } catch (Exception e) {
            log.warn("Could not parse LLM JSON response, storing raw text. Error: {}", e.getMessage());
            result.setSummary(raw);
        }
    }

    private static String extractJsonObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }

    private void sendEvent(UUID documentId, AnalysisProgressEvent event) {
        SseEmitter emitter = emitters.get(documentId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(event));
        } catch (IOException e) {
            log.debug("SSE emitter disconnected for {}", documentId);
            emitters.remove(documentId);
        }
    }

    private Document.FileType detectFileType(String contentType) {
        if (contentType == null) return Document.FileType.IMAGE;
        return contentType.equalsIgnoreCase("application/pdf")
                ? Document.FileType.PDF
                : Document.FileType.IMAGE;
    }
}
