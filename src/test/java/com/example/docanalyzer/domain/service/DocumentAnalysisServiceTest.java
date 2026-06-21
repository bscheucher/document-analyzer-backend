package com.example.docanalyzer.domain.service;

import com.example.docanalyzer.domain.model.AnalysisResult;
import com.example.docanalyzer.domain.model.Document;
import com.example.docanalyzer.domain.model.DocumentStatus;
import com.example.docanalyzer.domain.model.FileType;
import com.example.docanalyzer.domain.model.User;
import com.example.docanalyzer.domain.port.in.UploadCommand;
import com.example.docanalyzer.domain.port.out.DocumentRepositoryPort;
import com.example.docanalyzer.domain.port.out.LlmPort;
import com.example.docanalyzer.domain.port.out.ProgressNotifier;
import com.example.docanalyzer.domain.port.out.StoragePort;
import com.example.docanalyzer.domain.port.out.TextExtractorPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentAnalysisServiceTest {

    @Mock DocumentRepositoryPort documentRepository;
    @Mock StoragePort storageService;
    @Mock LlmPort llmService;
    @Mock TextExtractorPort textExtractor;
    @Mock ProgressNotifier progressNotifier;

    DocumentAnalysisService service;
    User owner;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setEmail("test@example.com");
        // threshold=100, chunkSize=50, overlap=10 so the chunking tests can
        // exercise the path with tiny synthetic inputs.
        service = new DocumentAnalysisService(
                documentRepository, storageService, llmService, textExtractor,
                progressNotifier, new ChunkingConfig(100, 50, 10),
                new LlmResponseParser(new ObjectMapper()));
    }

    private static UploadCommand uploadCommand(User owner, String filename, String contentType) {
        return new UploadCommand(owner, filename, contentType, 123L,
                new ByteArrayInputStream("dummy content".getBytes()));
    }

    @Test
    void upload_pdf_savesDocumentWithCorrectFileType() throws IOException {
        when(storageService.store(any(InputStream.class), eq("test.pdf"))).thenReturn("stored-name.pdf");
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document doc = service.upload(uploadCommand(owner, "test.pdf", "application/pdf"));

        assertThat(doc.getFileType()).isEqualTo(FileType.PDF);
        assertThat(doc.getFilename()).isEqualTo("test.pdf");
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(doc.getOwner()).isSameAs(owner);
        verify(documentRepository).save(any(Document.class));
    }

    @Test
    void upload_image_detectsImageFileType() throws IOException {
        when(storageService.store(any(InputStream.class), eq("scan.jpg"))).thenReturn("stored-scan.jpg");
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document doc = service.upload(uploadCommand(owner, "scan.jpg", "image/jpeg"));

        assertThat(doc.getFileType()).isEqualTo(FileType.IMAGE);
    }

    // ── analyze pipeline ─────────────────────────────────────────────────────

    @Test
    void analyze_image_happyPath_parsesJsonAndPassesContentType() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(documentRepository.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes("scan.bin")).thenReturn(new byte[]{1, 2, 3});
        when(llmService.analyzeImage(any(), eq("image/png"))).thenReturn("""
                {"documentType":"Invoice","summary":"Q1 invoice","keyTopics":["billing","Q1"]}
                """);

        service.analyze(id);

        verify(documentRepository).updateStatus(id, DocumentStatus.EXTRACTING);
        verify(documentRepository).updateStatus(id, DocumentStatus.ANALYZING);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(documentRepository).completeAnalysis(eq(id), captor.capture());
        AnalysisResult result = captor.getValue();
        assertThat(result.getDocumentType()).isEqualTo("Invoice");
        assertThat(result.getSummary()).isEqualTo("Q1 invoice");
        assertThat(result.getKeyTopics()).containsExactly("billing", "Q1");
        verify(documentRepository, never()).failAnalysis(any(), any(), any());
        verify(progressNotifier).complete(id);
    }

    @Test
    void analyze_image_nullContentType_fallsBackToJpeg() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, null);

        when(documentRepository.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{1});
        when(llmService.analyzeImage(any(), eq("image/jpeg")))
                .thenReturn("{\"documentType\":\"X\",\"summary\":\"y\",\"keyTopics\":[]}");

        service.analyze(id);

        verify(llmService).analyzeImage(any(), eq("image/jpeg"));
    }

    @Test
    void analyze_llmThrows_persistsFailedWithMessage() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(documentRepository.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        when(llmService.analyzeImage(any(), any()))
                .thenThrow(new RuntimeException("Ollama unreachable"));

        service.analyze(id);

        verify(documentRepository).failAnalysis(eq(id), any(AnalysisResult.class), eq("Ollama unreachable"));
        verify(documentRepository, never()).completeAnalysis(any(), any());
        verify(progressNotifier).complete(id);
    }

    @Test
    void analyze_parsesJsonWrappedInProseAndFences() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(documentRepository.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        when(llmService.analyzeImage(any(), any())).thenReturn(
                "Here's the analysis:\n```json\n" +
                        "{\"documentType\":\"Letter\",\"summary\":\"S\",\"keyTopics\":[\"a\"]}\n" +
                        "```\nLet me know if you need more.");

        service.analyze(id);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(documentRepository).completeAnalysis(eq(id), captor.capture());
        assertThat(captor.getValue().getDocumentType()).isEqualTo("Letter");
        assertThat(captor.getValue().getKeyTopics()).containsExactly("a");
    }

    @Test
    void analyze_picksRealJson_whenExampleBlockPrecedesIt() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(documentRepository.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        // First {...} is an example structure with no expected fields; the
        // real analysis follows.
        when(llmService.analyzeImage(any(), any())).thenReturn(
                "Example: {\"foo\": \"bar\"}.\nResult:\n" +
                        "{\"documentType\":\"Invoice\",\"summary\":\"Real\",\"keyTopics\":[\"x\"]}");

        service.analyze(id);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(documentRepository).completeAnalysis(eq(id), captor.capture());
        assertThat(captor.getValue().getDocumentType()).isEqualTo("Invoice");
        assertThat(captor.getValue().getSummary()).isEqualTo("Real");
        assertThat(captor.getValue().getKeyTopics()).containsExactly("x");
    }

    @Test
    void analyze_handlesBracesInsideStringValues() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(documentRepository.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        when(llmService.analyzeImage(any(), any())).thenReturn(
                "{\"documentType\":\"Letter\"," +
                        "\"summary\":\"Body mentions a } char and \\\"quotes\\\".\"," +
                        "\"keyTopics\":[\"a\"]}");

        service.analyze(id);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(documentRepository).completeAnalysis(eq(id), captor.capture());
        assertThat(captor.getValue().getSummary())
                .isEqualTo("Body mentions a } char and \"quotes\".");
        assertThat(captor.getValue().getDocumentType()).isEqualTo("Letter");
    }

    @Test
    void analyze_nonJsonResponse_storesRawAsSummary() throws IOException {
        UUID id = UUID.randomUUID();
        Document doc = imageDoc(id, "image/png");

        when(documentRepository.loadWithResult(id)).thenReturn(doc);
        when(storageService.readBytes(any())).thenReturn(new byte[]{0});
        when(llmService.analyzeImage(any(), any())).thenReturn("Sorry, I cannot analyze this.");

        service.analyze(id);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(documentRepository).completeAnalysis(eq(id), captor.capture());
        AnalysisResult result = captor.getValue();
        assertThat(result.getSummary()).isEqualTo("Sorry, I cannot analyze this.");
        assertThat(result.getDocumentType()).isNull();
        assertThat(result.getKeyTopics()).isNull();
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
        doc.setFileType(FileType.IMAGE);
        doc.setStoragePath("scan.bin");
        doc.setContentType(contentType);
        doc.setStatus(DocumentStatus.PENDING);
        return doc;
    }
}
