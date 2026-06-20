package com.example.docanalyzer.controller;

import com.example.docanalyzer.dto.AnalysisResultDto;
import com.example.docanalyzer.dto.DocumentDetailResponse;
import com.example.docanalyzer.dto.DocumentUploadResponse;
import com.example.docanalyzer.entity.AnalysisResult;
import com.example.docanalyzer.entity.Document;
import com.example.docanalyzer.repository.DocumentRepository;
import com.example.docanalyzer.service.DocumentAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentAnalysisService analysisService;
    private final DocumentRepository documentRepository;

    /**
     * POST /api/documents/upload
     * Accepts a multipart file, saves it, kicks off async analysis.
     */
    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file) throws IOException {

        validateFile(file);
        Document doc = analysisService.upload(file);

        // Fire-and-forget: start analysis in background thread
        analysisService.analyzeAsync(doc.getId());

        return ResponseEntity.accepted().body(toUploadResponse(doc));
    }

    /**
     * GET /api/documents/{id}/stream
     * SSE endpoint — client subscribes and receives progress events.
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id) {
        return analysisService.subscribe(id);
    }

    /**
     * GET /api/documents/{id}
     * Fetch the current state + analysis result of a document.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentDetailResponse> getDocument(@PathVariable UUID id) {
        return documentRepository.findByIdWithResult(id)
                .map(this::toDetailResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/documents
     * List all documents (latest first).
     */
    @GetMapping
    public List<DocumentDetailResponse> list() {
        return documentRepository.findAllWithResults().stream()
                .map(this::toDetailResponse)
                .toList();
    }

    /**
     * DELETE /api/documents/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return analysisService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }
        if (file.getSize() > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("File size must not exceed 50 MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.startsWith("image/") && !contentType.equals("application/pdf"))) {
            throw new IllegalArgumentException("Only PDF and image files are supported");
        }

        byte[] header = new byte[12];
        try (InputStream is = file.getInputStream()) {
            int read = is.readNBytes(header, 0, header.length);
            if (read < 4) {
                throw new IllegalArgumentException("File too small to identify");
            }
        }
        if (!isSupportedFileType(header)) {
            throw new IllegalArgumentException(
                    "File contents do not match a supported type (PDF, JPEG, PNG, GIF, WebP)");
        }
    }

    private static boolean isSupportedFileType(byte[] h) {
        // PDF: %PDF-
        if (h[0] == 0x25 && h[1] == 0x50 && h[2] == 0x44 && h[3] == 0x46 && h[4] == 0x2D) return true;
        // JPEG: FF D8 FF
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) return true;
        // GIF: GIF87a / GIF89a
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8'
                && (h[4] == '7' || h[4] == '9') && h[5] == 'a') return true;
        // WebP: RIFF....WEBP
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return true;
        return false;
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private DocumentUploadResponse toUploadResponse(Document doc) {
        return new DocumentUploadResponse(
                doc.getId(), doc.getFilename(), doc.getFileType(),
                doc.getFileSize(), doc.getStatus(), doc.getCreatedAt()
        );
    }

    private DocumentDetailResponse toDetailResponse(Document doc) {
        AnalysisResult ar = doc.getAnalysisResult();
        AnalysisResultDto resultDto = ar == null ? null : new AnalysisResultDto(
                ar.getSummary(), ar.getDocumentType(), ar.getKeyTopics(),
                ar.getExtractedText(), ar.getErrorMessage()
        );
        return new DocumentDetailResponse(
                doc.getId(), doc.getFilename(), doc.getFileType(),
                doc.getFileSize(), doc.getStatus(), doc.getCreatedAt(), resultDto
        );
    }
}
