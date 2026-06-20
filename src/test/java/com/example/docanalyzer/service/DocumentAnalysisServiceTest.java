package com.example.docanalyzer.service;

import com.example.docanalyzer.entity.AnalysisResult;
import com.example.docanalyzer.entity.Document;
import com.example.docanalyzer.entity.User;
import com.example.docanalyzer.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentAnalysisServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock DocumentPersistenceService persistence;
    @Mock StorageService storageService;
    @Mock LlmService llmService;

    DocumentAnalysisService service;
    User owner;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setEmail("test@example.com");
        service = new DocumentAnalysisService(
                documentRepository, persistence, storageService, llmService, new ObjectMapper()
        );
        // @Value-injected fields aren't set by the no-Spring constructor — pick
        // small values so the chunking tests can exercise the path with tiny
        // synthetic inputs.
        ReflectionTestUtils.setField(service, "chunkingThresholdChars", 100);
        ReflectionTestUtils.setField(service, "chunkSizeChars", 50);
        ReflectionTestUtils.setField(service, "chunkOverlapChars", 10);
    }

    @Test
    void upload_pdf_savesDocumentWithCorrectFileType() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "dummy content".getBytes()
        );

        when(storageService.store(file)).thenReturn("stored-name.pdf");
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document doc = inv.getArgument(0);
            return doc;
        });

        Document doc = service.upload(file, owner);

        assertThat(doc.getFileType()).isEqualTo(Document.FileType.PDF);
        assertThat(doc.getFilename()).isEqualTo("test.pdf");
        assertThat(doc.getStatus()).isEqualTo(Document.DocumentStatus.PENDING);
        assertThat(doc.getOwner()).isSameAs(owner);
        verify(documentRepository).save(any(Document.class));
    }

    @Test
    void upload_image_detectsImageFileType() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.jpg", "image/jpeg", "dummy bytes".getBytes()
        );

        when(storageService.store(file)).thenReturn("stored-scan.jpg");
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document doc = service.upload(file, owner);

        assertThat(doc.getFileType()).isEqualTo(Document.FileType.IMAGE);
    }

    @Test
    void subscribe_returnsEmitter_forKnownDocument() {
        UUID id = UUID.randomUUID();
        Document doc = new Document();
        doc.setStatus(Document.DocumentStatus.PENDING);

        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));

        var emitter = service.subscribe(id);

        assertThat(emitter).isNotNull();
    }

    @Test
    void subscribe_reconnect_completesPriorEmitter() {
        UUID id = UUID.randomUUID();
        Document doc = new Document();
        doc.setStatus(Document.DocumentStatus.PENDING);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));

        var first = service.subscribe(id);
        var second = service.subscribe(id);

        // The first emitter must be in a terminal state — sending on a
        // completed SseEmitter throws IllegalStateException.
        assertThat(first).isNotSameAs(second);
        assertThatThrownBy(() -> first.send("late"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── analyzeAsync pipeline ────────────────────────────────────────────────

    @Test
    void analyzeAsync_image_happyPath_parsesJsonAndPassesContentType() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(persistence.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes("scan.bin")).thenReturn(new byte[]{1, 2, 3});
        when(llmService.analyzeImage(any(), eq("image/png"))).thenReturn("""
                {"documentType":"Invoice","summary":"Q1 invoice","keyTopics":["billing","Q1"]}
                """);

        service.analyzeAsync(id);

        verify(persistence).updateStatus(id, Document.DocumentStatus.EXTRACTING);
        verify(persistence).updateStatus(id, Document.DocumentStatus.ANALYZING);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(persistence).completeAnalysis(eq(id), captor.capture());
        AnalysisResult result = captor.getValue();
        assertThat(result.getDocumentType()).isEqualTo("Invoice");
        assertThat(result.getSummary()).isEqualTo("Q1 invoice");
        assertThat(result.getKeyTopics()).containsExactly("billing", "Q1");
        verify(persistence, never()).failAnalysis(any(), any(), any());
    }

    @Test
    void analyzeAsync_image_nullContentType_fallsBackToJpeg() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, null);

        when(persistence.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{1});
        when(llmService.analyzeImage(any(), eq("image/jpeg")))
                .thenReturn("{\"documentType\":\"X\",\"summary\":\"y\",\"keyTopics\":[]}");

        service.analyzeAsync(id);

        verify(llmService).analyzeImage(any(), eq("image/jpeg"));
    }

    @Test
    void analyzeAsync_llmThrows_persistsFailedWithMessage() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(persistence.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        when(llmService.analyzeImage(any(), any()))
                .thenThrow(new RuntimeException("Ollama unreachable"));

        service.analyzeAsync(id);

        verify(persistence).failAnalysis(eq(id), any(AnalysisResult.class), eq("Ollama unreachable"));
        verify(persistence, never()).completeAnalysis(any(), any());
    }

    @Test
    void analyzeAsync_parsesJsonWrappedInProseAndFences() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(persistence.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        when(llmService.analyzeImage(any(), any())).thenReturn(
                "Here's the analysis:\n```json\n" +
                        "{\"documentType\":\"Letter\",\"summary\":\"S\",\"keyTopics\":[\"a\"]}\n" +
                        "```\nLet me know if you need more.");

        service.analyzeAsync(id);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(persistence).completeAnalysis(eq(id), captor.capture());
        assertThat(captor.getValue().getDocumentType()).isEqualTo("Letter");
        assertThat(captor.getValue().getKeyTopics()).containsExactly("a");
    }

    @Test
    void analyzeAsync_picksRealJson_whenExampleBlockPrecedesIt() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(persistence.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        // First {...} is an example structure with no expected fields; the
        // real analysis follows. The old indexOf/lastIndexOf slicer would
        // grab everything from the first { to the last }, breaking the parse.
        when(llmService.analyzeImage(any(), any())).thenReturn(
                "Example: {\"foo\": \"bar\"}.\nResult:\n" +
                        "{\"documentType\":\"Invoice\",\"summary\":\"Real\",\"keyTopics\":[\"x\"]}");

        service.analyzeAsync(id);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(persistence).completeAnalysis(eq(id), captor.capture());
        assertThat(captor.getValue().getDocumentType()).isEqualTo("Invoice");
        assertThat(captor.getValue().getSummary()).isEqualTo("Real");
        assertThat(captor.getValue().getKeyTopics()).containsExactly("x");
    }

    @Test
    void analyzeAsync_handlesBracesInsideStringValues() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(persistence.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        when(llmService.analyzeImage(any(), any())).thenReturn(
                "{\"documentType\":\"Letter\"," +
                        "\"summary\":\"Body mentions a } char and \\\"quotes\\\".\"," +
                        "\"keyTopics\":[\"a\"]}");

        service.analyzeAsync(id);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(persistence).completeAnalysis(eq(id), captor.capture());
        assertThat(captor.getValue().getSummary())
                .isEqualTo("Body mentions a } char and \"quotes\".");
        assertThat(captor.getValue().getDocumentType()).isEqualTo("Letter");
    }

    @Test
    void analyzeAsync_nonJsonResponse_storesRawAsSummary() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(persistence.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        when(llmService.analyzeImage(any(), any())).thenReturn("Sorry, I cannot analyze this.");

        service.analyzeAsync(id);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(persistence).completeAnalysis(eq(id), captor.capture());
        AnalysisResult result = captor.getValue();
        assertThat(result.getSummary()).isEqualTo("Sorry, I cannot analyze this.");
        assertThat(result.getDocumentType()).isNull();
        assertThat(result.getKeyTopics()).isNull();
    }

    // ── chunkText (pure function) ────────────────────────────────────────────

    @Test
    void chunkText_shorterThanChunkSize_returnsSingleChunk() {
        List<String> chunks = DocumentAnalysisService.chunkText("hello", 100, 10);
        assertThat(chunks).containsExactly("hello");
    }

    @Test
    void chunkText_emptyOrNull_returnsEmpty() {
        assertThat(DocumentAnalysisService.chunkText(null, 100, 10)).isEmpty();
        assertThat(DocumentAnalysisService.chunkText("", 100, 10)).isEmpty();
    }

    @Test
    void chunkText_longerThanChunkSize_splitsWithOverlap() {
        // 30 chars, chunk=10, overlap=2 → step=8, so chunks start at 0, 8, 16, 24.
        String text = "abcdefghijklmnopqrstuvwxyz1234";
        List<String> chunks = DocumentAnalysisService.chunkText(text, 10, 2);

        assertThat(chunks).containsExactly(
                "abcdefghij",   // 0..10
                "ijklmnopqr",   // 8..18  — first 2 chars overlap with previous
                "qrstuvwxyz",   // 16..26
                "yz1234"        // 24..30 — short tail
        );
    }

    @Test
    void chunkText_invalidConfig_throws() {
        assertThatThrownBy(() -> DocumentAnalysisService.chunkText("x", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentAnalysisService.chunkText("x", 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentAnalysisService.chunkText("x", 10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── analyzeTextMaybeChunked orchestration ────────────────────────────────

    @Test
    void analyzeTextMaybeChunked_underThreshold_callsAnalyzeTextOnce() {
        UUID id = UUID.randomUUID();
        when(llmService.analyzeText("short text")).thenReturn("{\"summary\":\"S\"}");

        String result = service.analyzeTextMaybeChunked(id, "short text");

        assertThat(result).isEqualTo("{\"summary\":\"S\"}");
        verify(llmService).analyzeText("short text");
        verify(llmService, never()).analyzeTextChunk(any(), anyInt(), anyInt());
        verify(llmService, never()).mergePartialSummaries(any());
    }

    @Test
    void analyzeTextMaybeChunked_overThreshold_chunksAndMerges() {
        UUID id = UUID.randomUUID();
        // threshold=100, chunkSize=50, overlap=10 → step=40, so a 130-char
        // input splits into chunks starting at 0, 40, 80 (3 chunks).
        String text = "a".repeat(130);

        when(llmService.analyzeTextChunk(any(), anyInt(), eq(3)))
                .thenReturn("{\"summary\":\"partial\"}");
        when(llmService.mergePartialSummaries(any()))
                .thenReturn("{\"summary\":\"merged\",\"documentType\":\"X\"}");

        String result = service.analyzeTextMaybeChunked(id, text);

        assertThat(result).isEqualTo("{\"summary\":\"merged\",\"documentType\":\"X\"}");
        verify(llmService, never()).analyzeText(any());
        verify(llmService).analyzeTextChunk(any(), eq(1), eq(3));
        verify(llmService).analyzeTextChunk(any(), eq(2), eq(3));
        verify(llmService).analyzeTextChunk(any(), eq(3), eq(3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> partialsCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService).mergePartialSummaries(partialsCaptor.capture());
        assertThat(partialsCaptor.getValue()).hasSize(3);
        assertThat(partialsCaptor.getValue()).allMatch(p -> p.contains("partial"));
    }

    private static Document imageDoc(UUID id, String contentType) {
        Document doc = new Document();
        doc.setId(id);
        doc.setFileType(Document.FileType.IMAGE);
        doc.setStoragePath("scan.bin");
        doc.setContentType(contentType);
        doc.setStatus(Document.DocumentStatus.PENDING);
        return doc;
    }
}
