package com.example.docanalyzer.config;

import com.example.docanalyzer.domain.port.in.DocumentAnalysisUseCase;
import com.example.docanalyzer.domain.port.out.DocumentRepositoryPort;
import com.example.docanalyzer.web.AsyncAnalysisLauncher;
import com.example.docanalyzer.web.CurrentUserProvider;
import com.example.docanalyzer.web.DocumentController;
import com.example.docanalyzer.web.SseProgressNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DocumentController.class)
@Import(WebMvcConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://allowed.example")
class CorsConfigTest {

    @Autowired MockMvc mockMvc;
    @MockBean DocumentAnalysisUseCase analysisService;
    @MockBean DocumentRepositoryPort documentRepository;
    @MockBean CurrentUserProvider currentUserProvider;
    @MockBean AsyncAnalysisLauncher analysisLauncher;
    @MockBean SseProgressNotifier progressNotifier;

    @Test
    void preflight_fromAllowedOrigin_returnsAllowOriginHeader() throws Exception {
        mockMvc.perform(options("/api/documents")
                        .header("Origin", "http://allowed.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://allowed.example"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    void preflight_fromDisallowedOrigin_isRejected() throws Exception {
        mockMvc.perform(options("/api/documents")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
