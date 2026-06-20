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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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

    @Value("${app.ai.chunking.threshold-chars:20000}")
    private int chunkingThresholdChars;

    @Value("${app.ai.chunking.chunk-size-chars:6000}")
    private int chunkSizeChars;

    @Value("${app.ai.chunking.overlap-chars:200}")
    private int chunkOverlapChars;

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

        SseEmitter previous = emitters.put(documentId, emitter);
        if (previous != null) {
            // Reconnect: terminate the stale connection cleanly so the old
            // request thread is released instead of waiting for its timeout.
            try {
                previous.complete();
            } catch (Exception ignored) {
                // best-effort: prior emitter may already be in a terminal state
            }
        }

        // remove(key, value) so a late callback from an already-replaced
        // emitter cannot evict the current one from the map.
        emitter.onCompletion(() -> emitters.remove(documentId, emitter));
        emitter.onTimeout(() -> emitters.remove(documentId, emitter));
        emitter.onError(e -> emitters.remove(documentId, emitter));

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
                    : analyzeTextMaybeChunked(documentId, extractedText);

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

    /**
     * Single LLM call when the extracted text fits comfortably, otherwise
     * chunked map-reduce: summarise each chunk, then ask the model to
     * consolidate the partials into a final analysis.
     */
    String analyzeTextMaybeChunked(UUID documentId, String text) {
        if (text == null || text.length() <= chunkingThresholdChars) {
            return llmService.analyzeText(text == null ? "" : text);
        }

        List<String> chunks = chunkText(text, chunkSizeChars, chunkOverlapChars);
        log.info("Chunked document {} into {} chunks ({} chars)",
                documentId, chunks.size(), text.length());

        List<String> partials = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            sendEvent(documentId, new AnalysisProgressEvent("ANALYZING",
                    String.format("Analyzing chunk %d of %d...", i + 1, chunks.size()),
                    50 + (40 * (i + 1)) / (chunks.size() + 1)));
            partials.add(llmService.analyzeTextChunk(chunks.get(i), i + 1, chunks.size()));
        }

        sendEvent(documentId, new AnalysisProgressEvent("ANALYZING",
                "Consolidating chunk summaries...", 90));
        return llmService.mergePartialSummaries(partials);
    }

    /**
     * Slides a window of {@code chunkSize} chars across {@code text}, advancing
     * by {@code chunkSize - overlap} each step so consecutive chunks share
     * {@code overlap} chars of context.
     */
    static List<String> chunkText(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be in [0, chunkSize)");
        }
        if (text == null || text.isEmpty()) return List.of();
        if (text.length() <= chunkSize) return List.of(text);

        List<String> chunks = new ArrayList<>();
        int step = chunkSize - overlap;
        for (int pos = 0; pos < text.length(); pos += step) {
            int end = Math.min(pos + chunkSize, text.length());
            chunks.add(text.substring(pos, end));
            if (end == text.length()) break;
        }
        return chunks;
    }

    private void parseAndApplyLlmResponse(String raw, AnalysisResult result) {
        for (String candidate : extractJsonCandidates(raw)) {
            try {
                JsonNode json = objectMapper.readTree(candidate);
                if (!hasAnyExpectedField(json)) continue;

                result.setSummary(json.path("summary").asText());
                result.setDocumentType(json.path("documentType").asText());

                JsonNode topics = json.path("keyTopics");
                if (topics.isArray()) {
                    List<String> topicList = objectMapper.convertValue(
                            topics, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    result.setKeyTopics(topicList);
                }
                return;
            } catch (Exception ignored) {
                // not parseable as JSON — try the next candidate
            }
        }
        log.warn("No usable JSON object found in LLM response, storing raw text.");
        result.setSummary(raw);
    }

    private static boolean hasAnyExpectedField(JsonNode json) {
        return json.has("summary") || json.has("documentType") || json.has("keyTopics");
    }

    /**
     * Returns every depth-balanced {...} substring in the input, in order.
     * String literals are skipped so braces inside quoted values don't
     * mis-balance the scan.
     */
    private static List<String> extractJsonCandidates(String raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> candidates = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    candidates.add(raw.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return candidates;
    }

    private void sendEvent(UUID documentId, AnalysisProgressEvent event) {
        SseEmitter emitter = emitters.get(documentId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(event));
        } catch (IOException | IllegalStateException e) {
            // IOException: client disconnected. IllegalStateException: emitter
            // was completed under us (e.g. replaced by a reconnect).
            log.debug("SSE emitter unavailable for {}: {}", documentId, e.getMessage());
            emitters.remove(documentId, emitter);
        }
    }

    private Document.FileType detectFileType(String contentType) {
        if (contentType == null) return Document.FileType.IMAGE;
        return contentType.equalsIgnoreCase("application/pdf")
                ? Document.FileType.PDF
                : Document.FileType.IMAGE;
    }
}
