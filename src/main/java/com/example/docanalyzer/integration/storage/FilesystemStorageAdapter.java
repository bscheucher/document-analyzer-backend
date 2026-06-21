package com.example.docanalyzer.integration.storage;

import com.example.docanalyzer.domain.port.out.StoragePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * {@link StoragePort} backed by the local filesystem.
 * Swap this out later for an Azure Blob Storage adapter by providing a second
 * {@code StoragePort} bean.
 */
@Slf4j
@Service
public class FilesystemStorageAdapter implements StoragePort {

    private final Path uploadDir;

    public FilesystemStorageAdapter(@Value("${app.storage.upload-dir}") String uploadDirPath) throws IOException {
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
        log.info("Storage directory: {}", this.uploadDir);
    }

    @Override
    public String store(InputStream content, String originalFilename) throws IOException {
        String extension = getExtension(originalFilename);
        String storedName = UUID.randomUUID() + "." + extension;
        Path target = uploadDir.resolve(storedName);

        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);

        log.debug("Stored file {} → {}", originalFilename, target);
        return storedName; // relative key — resolves via load()
    }

    public Path load(String storageKey) {
        if (storageKey == null) {
            throw new IllegalArgumentException("storageKey must not be null");
        }
        Path resolved = uploadDir.resolve(storageKey).normalize();
        if (!resolved.startsWith(uploadDir)) {
            // Today storageKey is always a UUID we generated, but guard
            // anyway so a bad value can never escape the upload directory.
            throw new IllegalArgumentException("Resolved path escapes the upload directory");
        }
        return resolved;
    }

    @Override
    public byte[] readBytes(String storageKey) throws IOException {
        return Files.readAllBytes(load(storageKey));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(load(storageKey));
    }

    /**
     * Returns a safe lowercase extension (a-z0-9, ≤10 chars) or "bin" if the
     * filename has no usable extension. Sanitised so that a malicious
     * upload like "x.txt/../../etc/passwd" cannot inject path separators
     * into the stored filename.
     */
    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "bin";
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return ext.matches("[a-z0-9]{1,10}") ? ext : "bin";
    }
}
