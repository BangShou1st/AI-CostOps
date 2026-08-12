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
    }
}
