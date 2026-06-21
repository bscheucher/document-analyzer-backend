package com.example.docanalyzer.integration.extraction;

import com.example.docanalyzer.domain.port.out.TextExtractorPort;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * {@link TextExtractorPort} backed by Apache PDFBox. Only PDFs carry extractable
 * text; every other content type yields {@code ""} (images are handled by the
 * vision model instead).
 */
@Slf4j
@Service
public class PdfBoxTextExtractor implements TextExtractorPort {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    @Override
    public String extractText(byte[] content, String contentType) {
        if (!PDF_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            return "";
        }
        try (PDDocument pdf = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(pdf);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract text from PDF", e);
        }
    }
}
