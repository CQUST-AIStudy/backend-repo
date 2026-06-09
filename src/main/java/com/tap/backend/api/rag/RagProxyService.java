package com.tap.backend.api.rag;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class RagProxyService {

    private static final String HEADER_HOST = "host";
    private static final String HEADER_CONNECTION = "connection";
    private static final String HEADER_CONTENT_LENGTH = "content-length";
    private static final String HEADER_TRANSFER_ENCODING = "transfer-encoding";
    private static final String HEADER_AUTHORIZATION = "authorization";

    private final OkHttpClient httpClient;
    private final String ragBaseUrl;

    public RagProxyService(@Value("${tap.rag.proxy.base-url:http://127.0.0.1:8001}") String ragBaseUrl) {
        this.ragBaseUrl = trimTrailingSlash(ragBaseUrl);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public ResponseEntity<?> forward(HttpServletRequest request, String bearerToken) {
        String targetUrl = buildTargetUrl(request);
        Request.Builder builder = new Request.Builder().url(targetUrl);
        copyRequestHeaders(request, builder);
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken.trim());
        }

        String method = request.getMethod();
        RequestBody body = buildRequestBody(request, method);
        builder.method(method, permitsRequestBody(method) ? body : null);

        try {
            Response response = httpClient.newCall(builder.build()).execute();
            return toResponseEntity(response);
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .header(HttpHeaders.CONTENT_TYPE, org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
                    .body(("{\"message\":\"RAG service unavailable: " + sanitize(ex.getMessage()) + "\"}").getBytes());
        }
    }

    private ResponseEntity<?> toResponseEntity(Response response) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        copyResponseHeaders(response, headers);

        ResponseBody responseBody = response.body();
        if (isEventStream(headers.getFirst(HttpHeaders.CONTENT_TYPE))) {
            StreamingResponseBody stream = outputStream -> {
                try (response; InputStream inputStream = responseBody == null ? InputStream.nullInputStream() : responseBody.byteStream()) {
                    inputStream.transferTo(outputStream);
                    outputStream.flush();
                }
            };
            return ResponseEntity.status(response.code()).headers(headers).body(stream);
        }

        try (response) {
            byte[] payload = responseBody == null ? new byte[0] : responseBody.bytes();
            return ResponseEntity.status(response.code()).headers(headers).body(payload);
        }
    }

    private void copyRequestHeaders(HttpServletRequest request, Request.Builder builder) {
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (shouldSkipRequestHeader(headerName)) {
                continue;
            }
            Enumeration<String> headerValues = request.getHeaders(headerName);
            while (headerValues != null && headerValues.hasMoreElements()) {
                builder.addHeader(headerName, headerValues.nextElement());
            }
        }
    }

    private void copyResponseHeaders(Response response, HttpHeaders headers) {
        response.headers().forEach(pair -> {
            String headerName = pair.getFirst();
            if (shouldSkipResponseHeader(headerName)) {
                return;
            }
            headers.add(headerName, pair.getSecond());
        });
    }

    private RequestBody buildRequestBody(HttpServletRequest request, String method) {
        if (!permitsRequestBody(method)) {
            return null;
        }
        try {
            byte[] bytes = request.getInputStream().readAllBytes();
            MediaType mediaType = parseMediaType(request.getContentType());
            return RequestBody.create(bytes, mediaType);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read request body", ex);
        }
    }

    private String buildTargetUrl(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String query = request.getQueryString();
        return ragBaseUrl + requestUri + (query == null || query.isBlank() ? "" : "?" + query);
    }

    private boolean permitsRequestBody(String method) {
        return !"GET".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method);
    }

    private boolean isEventStream(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("text/event-stream");
    }

    private boolean shouldSkipRequestHeader(String headerName) {
        if (headerName == null) {
            return true;
        }
        String normalized = headerName.trim().toLowerCase();
        return HEADER_HOST.equals(normalized)
                || HEADER_CONNECTION.equals(normalized)
                || HEADER_CONTENT_LENGTH.equals(normalized)
                || HEADER_AUTHORIZATION.equals(normalized);
    }

    private boolean shouldSkipResponseHeader(String headerName) {
        if (headerName == null) {
            return true;
        }
        String normalized = headerName.trim().toLowerCase();
        return HEADER_TRANSFER_ENCODING.equals(normalized)
                || HEADER_CONNECTION.equals(normalized)
                || HEADER_CONTENT_LENGTH.equals(normalized);
    }

    private MediaType parseMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.parse(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE);
        }
        MediaType mediaType = MediaType.parse(contentType);
        if (mediaType != null) {
            return mediaType;
        }
        return MediaType.parse(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "http://127.0.0.1:8001";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String sanitize(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("\\", "\\\\").replace("\"", "'");
    }
}
