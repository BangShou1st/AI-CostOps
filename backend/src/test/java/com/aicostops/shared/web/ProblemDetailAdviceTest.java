package com.aicostops.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProblemDetailAdviceTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ProblemDetailAdvice())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void mapsTypedErrorsToTraceableProblemDetails() throws Exception {
        var result = mockMvc.perform(get("/failure"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Foundation conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("The requested foundation state conflicts."))
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER))
                .andReturn();

        var headerTraceId = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
        var body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("\"traceId\":\"" + headerTraceId + "\"");
    }

    @Test
    void mapsInvalidEnumQueryToMalformedProblemDetail() throws Exception {
        assertMalformed("/projects?status=BOGUS");
    }

    @Test
    void mapsInvalidNumericQueryToMalformedProblemDetail() throws Exception {
        assertMalformed("/projects?page=x");
    }

    @Test
    void mapsInvalidNumericPathToMalformedProblemDetail() throws Exception {
        assertMalformed("/projects/not-a-number");
    }

    private void assertMalformed(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://aicostops.dev/problems/request-malformed"))
                .andExpect(jsonPath("$.title").value("Malformed request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("A request parameter has an invalid value."))
                .andExpect(jsonPath("$.instance").value(path.substring(0, path.indexOf('?') < 0
                        ? path.length() : path.indexOf('?'))))
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @RestController
    static class FailingController {

        @GetMapping("/failure")
        void fail() {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    ProblemCode.STATE_CONFLICT,
                    "Foundation conflict",
                    "The requested foundation state conflicts.");
        }

        @GetMapping("/projects")
        void list(@RequestParam(required = false) Status status,
                @RequestParam(defaultValue = "0") int page) {
        }

        @GetMapping("/projects/{id}")
        void get(@PathVariable long id) {
        }
    }

    enum Status {
        ACTIVE
    }
}
