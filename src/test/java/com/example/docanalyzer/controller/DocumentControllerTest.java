package com.example.docanalyzer.controller;

import com.example.docanalyzer.entity.Document;
import com.example.docanalyzer.entity.User;
import com.example.docanalyzer.repository.DocumentRepository;
import com.example.docanalyzer.service.CurrentUserProvider;
import com.example.docanalyzer.service.DocumentAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    @Mock DocumentAnalysisService analysisService;
    @Mock DocumentRepository documentRepository;
    @Mock CurrentUserProvider currentUserProvider;

    DocumentController controller;
    User owner;

    @BeforeEach
    void setUp() {
        controller = new DocumentController(analysisService, documentRepository, currentUserProvider);
        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setEmail("test@example.com");
        // Lenient because some upload-validation tests reject the request
        // before the controller ever resolves the user.
        org.mockito.Mockito.lenient().when(currentUserProvider.getCurrentUser()).thenReturn(owner);
    }

    // ── Positive paths: real magic-byte signatures pass ─────────────────────

    @Test
    void upload_validPdf_returnsAcceptedAndStartsAnalysis() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf",
                "%PDF-1.4\nsome content".getBytes());
        Document doc = uploadedDoc();
        when(analysisService.upload(file, owner)).thenReturn(doc);

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(analysisService).analyzeAsync(doc.getId());
    }

    @Test
    void upload_validJpeg_succeeds() throws IOException {
        byte[] jpeg = new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0, 0, 0, 0, 0, 0, 0, 0
        };
        MockMultipartFile file = new MockMultipartFile("file", "scan.jpg", "image/jpeg", jpeg);
        when(analysisService.upload(file, owner)).thenReturn(uploadedDoc());

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void upload_validPng_succeeds() throws IOException {
        byte[] png = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0
        };
        MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png", png);
        when(analysisService.upload(file, owner)).thenReturn(uploadedDoc());

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void upload_validGif_succeeds() throws IOException {
        byte[] gif = "GIF89a......".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "anim.gif", "image/gif", gif);
        when(analysisService.upload(file, owner)).thenReturn(uploadedDoc());

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void upload_validWebp_succeeds() throws IOException {
        byte[] webp = new byte[]{
                'R', 'I', 'F', 'F',
                0, 0, 0, 0,
                'W', 'E', 'B', 'P'
        };
        MockMultipartFile file = new MockMultipartFile("file", "pic.webp", "image/webp", webp);
        when(analysisService.upload(file, owner)).thenReturn(uploadedDoc());

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    // ── Negative paths ──────────────────────────────────────────────────────

    @Test
    void upload_emptyFile_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> controller.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void upload_unsupportedMimeType_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.txt", "text/plain", "hello world".getBytes());

        assertThatThrownBy(() -> controller.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only PDF and image files");
    }

    @Test
    void upload_nullMimeType_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "unknown.bin", null, "%PDF-1.4 fake".getBytes());

        assertThatThrownBy(() -> controller.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only PDF and image files");
    }

    @Test
    void upload_pdfMimeButWrongBytes_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf",
                "this is not actually a PDF document".getBytes());

        assertThatThrownBy(() -> controller.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    void upload_pngMimeButWrongBytes_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png",
                "definitely not a PNG file here".getBytes());

        assertThatThrownBy(() -> controller.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    void upload_tooSmallToIdentify_rejected() {
        // 3 bytes — readNBytes returns < 4
        MockMultipartFile file = new MockMultipartFile(
                "file", "tiny.pdf", "application/pdf",
                new byte[]{0x25, 0x50, 0x44});

        assertThatThrownBy(() -> controller.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void upload_oversizedFile_rejected() {
        byte[] big = new byte[50 * 1024 * 1024 + 1];
        big[0] = 0x25; big[1] = 0x50; big[2] = 0x44; big[3] = 0x46; big[4] = 0x2D;
        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.pdf", "application/pdf", big);

        assertThatThrownBy(() -> controller.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50 MB");
    }

    // ── Ownership scoping ───────────────────────────────────────────────────

    @Test
    void getDocument_notOwnedByCurrentUser_returns404() {
        // Repository returns empty when the doc either doesn't exist OR
        // belongs to a different owner; the controller can't distinguish
        // (intentionally — leaking existence enables enumeration attacks).
        UUID id = UUID.randomUUID();
        when(documentRepository.findByIdAndOwnerWithResult(id, owner.getId()))
                .thenReturn(java.util.Optional.empty());

        ResponseEntity<?> response = controller.getDocument(id);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void list_queriesByCurrentUserId() {
        when(documentRepository.findAllByOwnerWithResults(owner.getId()))
                .thenReturn(java.util.List.of());

        controller.list();

        verify(documentRepository).findAllByOwnerWithResults(owner.getId());
    }

    @Test
    void stream_notOwnedByCurrentUser_returns404() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findByIdAndOwner(id, owner.getId()))
                .thenReturn(java.util.Optional.empty());

        ResponseEntity<?> response = controller.stream(id);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(analysisService, org.mockito.Mockito.never()).subscribe(any());
    }

    @Test
    void delete_passesCurrentUserToService() {
        UUID id = UUID.randomUUID();
        when(analysisService.delete(id, owner)).thenReturn(true);

        ResponseEntity<?> response = controller.delete(id);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(analysisService).delete(id, owner);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static Document uploadedDoc() {
        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        doc.setFilename("any");
        doc.setFileType(Document.FileType.PDF);
        doc.setFileSize(100L);
        doc.setStatus(Document.DocumentStatus.PENDING);
        doc.setCreatedAt(Instant.now());
        return doc;
    }
}
