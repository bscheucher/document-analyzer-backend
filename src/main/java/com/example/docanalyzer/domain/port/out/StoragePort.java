package com.example.docanalyzer.domain.port.out;

import java.io.IOException;
import java.io.InputStream;

/**
 * Outbound port for binary blob storage of uploaded documents. Implemented by
 * an adapter in {@code integration.storage}. Framework-free: speaks only JDK
 * types so the domain never depends on Spring's {@code MultipartFile}.
 */
public interface StoragePort {

    /**
     * Persists the given content and returns an opaque storage key that
     * {@link #readBytes(String)} / {@link #delete(String)} can later resolve.
     * The caller retains ownership of {@code content} and must close it.
     */
    String store(InputStream content, String originalFilename) throws IOException;

    byte[] readBytes(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;
}
