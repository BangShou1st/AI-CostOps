package com.aicostops.shared.security;

import com.aicostops.shared.web.ProblemCode;
import com.aicostops.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityProblemWriter {
    private final ObjectMapper objectMapper;

    public SecurityProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void unauthorized(HttpServletRequest request, HttpServletResponse response,
            ProblemCode code, String detail) throws IOException {
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", code, detail);
    }

    public void forbidden(HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
        write(request, response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", ProblemCode.FORBIDDEN, detail);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status,
            String title, ProblemCode code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "https://aicostops.dev/problems/" + code.name().toLowerCase().replace('_', '-'));
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code.name());
        body.put("traceId", request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
