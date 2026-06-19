package com.example.docanalyzer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Saves uploaded files to local disk.
 * Swap this out later for an Azure Blob Storage implementation
 * by extracting an interface and providing a second @Bean.
 */
@Slf4j
@Service
public class StorageService {

    private final Path uploadDir;

    public StorageService(@Value("${app.storage.upload-dir}") String uploadDirPath) throws IOException {
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
        log.info("Storage directory: {}", this.uploadDir);
    }

    /**
     * Saves the file and returns its relative storage path.
     */
    public String store(MultipartFile file) throws IOException {
        String extension = getExtension(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + "." + extension;
        Path target = uploadDir.resolve(storedName);

        try (InputStream is = file.getInputStream()) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }

        log.debug("Stored file {} → {}", file.getOriginalFilename(), target);
        return storedName; // relative path — resolves via load()
    }

    public Path load(String storagePath) {
        return uploadDir.resolve(storagePath).normalize();
    }

    public byte[] readBytes(String storagePath) throws IOException {
        return Files.readAllBytes(load(storagePath));
    }

    public void delete(String storagePath) throws IOException {
        Path path = load(storagePath);
        Files.deleteIfExists(path);
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "bin";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
