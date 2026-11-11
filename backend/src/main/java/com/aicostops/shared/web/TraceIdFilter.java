package com.aicostops.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        // Accept a client-supplied correlation id only when it is bounded and
        // safe for log/context propagation; otherwise mint our own.
        var inbound = request.getHeader(TRACE_ID_HEADER);
        var traceId = inbound != null && SAFE_TRACE_ID.matcher(inbound).matches()
                ? inbound
                : "trc_" + UUID.randomUUID();
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
