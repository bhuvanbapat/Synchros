package com.synchros.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Captures the exact raw request bytes for the payment-webhook path so the
 * HMAC can be verified over precisely what the sender signed (byte-exact —
 * JSON re-serialization would break signatures). Only active on
 * /api/payment-webhooks/** to avoid buffering every request in the system.
 * The wrapped stream replays the same bytes to the downstream JSON parser.
 */
@Component
@Order(0)
public class RawBodyCaptureFilter extends OncePerRequestFilter {

    public static final String RAW_BODY_ATTRIBUTE = "rawRequestBody";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/payment-webhooks");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] raw = request.getInputStream().readAllBytes();
        HttpServletRequest wrapped = new CachedBodyRequest(request, raw);
        wrapped.setAttribute(RAW_BODY_ATTRIBUTE, raw);
        chain.doFilter(wrapped, response);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return buffer.read();
                }

                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // Synchronous reads only; async listener unsupported.
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }
}
