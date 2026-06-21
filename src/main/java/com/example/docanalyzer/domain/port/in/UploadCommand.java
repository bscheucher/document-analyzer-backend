package com.example.docanalyzer.domain.port.in;

import com.example.docanalyzer.domain.model.User;

import java.io.InputStream;

/**
 * Inbound command to store and register a freshly uploaded document. The web
 * layer builds this from its {@code MultipartFile}, keeping that framework type
 * out of the domain. The use case consumes (and closes) {@link #content()}.
 */
public record UploadCommand(
        User owner,
        String filename,
        String contentType,
        long size,
        InputStream content
) {}
