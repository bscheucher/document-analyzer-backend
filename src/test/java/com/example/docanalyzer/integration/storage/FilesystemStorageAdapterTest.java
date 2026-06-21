package com.example.docanalyzer.integration.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemStorageAdapterTest {

    @TempDir
    Path tempDir;

    private FilesystemStorageAdapter storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new FilesystemStorageAdapter(tempDir.toString());
    }

    // ── load() path-traversal guard ─────────────────────────────────────────

    @Test
    void load_validRelativeName_resolvesUnderUploadDir() {
        Path resolved = storage.load("abc.pdf");
        assertThat(resolved).isEqualTo(tempDir.resolve("abc.pdf").normalize());
    }

    @Test
    void load_relativeTraversal_isRejected() {
        assertThatThrownBy(() -> storage.load("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the upload directory");
    }

    @Test
    void load_absolutePath_isRejected() {
        assertThatThrownBy(() -> storage.load("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the upload directory");
    }

    @Test
    void load_null_isRejected() {
        assertThatThrownBy(() -> storage.load(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── store() extension sanitisation ──────────────────────────────────────

    @Test
    void store_filenameWithEvilExtension_isSanitisedToBin() throws IOException {
        // Without sanitisation, the path-separator-laden extension would let
        // Files.copy write outside the upload directory.
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.txt/../../etc/passwd", "application/pdf",
                "%PDF-1.4".getBytes());

        String storedName = storage.store(file.getInputStream(), file.getOriginalFilename());

        assertThat(storedName).endsWith(".bin");
        assertThat(storedName).doesNotContain("/");
        assertThat(storedName).doesNotContain("..");
        assertThat(tempDir.resolve(storedName)).exists();
    }

    @Test
    void store_normalPdfFilename_keepsPdfExtension() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.4".getBytes());

        String storedName = storage.store(file.getInputStream(), file.getOriginalFilename());

        assertThat(storedName).endsWith(".pdf");
        assertThat(tempDir.resolve(storedName)).exists();
    }

    @Test
    void store_uppercaseExtension_isLowercased() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.JPG", "image/jpeg",
                new byte[]{1, 2, 3});

        String storedName = storage.store(file.getInputStream(), file.getOriginalFilename());

        assertThat(storedName).endsWith(".jpg");
    }
}
