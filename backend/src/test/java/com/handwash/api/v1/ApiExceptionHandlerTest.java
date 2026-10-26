package com.handwash.api.v1;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void malformedJsonReturnsStableErrorDtoWithoutFrameworkInternals() throws Exception {
        mvc.perform(post("/api/v1/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("SOLICITUD_INVALIDA"))
            .andExpect(jsonPath("$.mensaje").exists())
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void unsupportedContentTypeUsesErrorDtoAndPreservesHttpStatus() throws Exception {
        mvc.perform(post("/api/v1/probe")
                .contentType(MediaType.TEXT_PLAIN)
                .content("value"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error").value("TIPO_CONTENIDO_NO_SOPORTADO"));
    }

    @Test
    void missingRequiredParameterReturnsStableErrorDto() throws Exception {
        mvc.perform(post("/api/v1/query"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("ENTRADA_REQUERIDA"));
    }

    @Test
    void missingMultipartPartReturnsStableErrorDto() throws Exception {
        mvc.perform(multipart("/api/v1/upload"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("ENTRADA_REQUERIDA"));
    }

    @RestController
    static class ProbeController {
        @PostMapping(value = "/api/v1/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        String probe(@RequestBody ProbeRequest request) {
            return request.value();
        }

        @PostMapping("/api/v1/query")
        String query(@RequestParam("value") String value) {
            return value;
        }

        @PostMapping(value = "/api/v1/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String upload(@RequestPart("file") MultipartFile file) {
            return file.getOriginalFilename();
        }
    }

    record ProbeRequest(String value) {}
}
