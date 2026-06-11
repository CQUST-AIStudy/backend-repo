package com.tap.backend.api.rag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@Service
public class RagProxyService {

    private static final String HEADER_HOST = "host";
    private static final String HEADER_CONNECTION = "connection";
    private static final String HEADER_CONTENT_LENGTH = "content-length";
    private static final String HEADER_CONTENT_TYPE = "content-type";
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

    public void forward(HttpServletRequest request, HttpServletResponse servletResponse, String bearerToken) {
        String targetUrl = buildTargetUrl(request);
        Request.Builder builder = new Request.Builder().url(targetUrl);
        copyRequestHeaders(request, builder, request instanceof MultipartHttpServletRequest);
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken.trim());
        }

        String method = request.getMethod();
        RequestBody body = buildRequestBody(request, method);
        builder.method(method, permitsRequestBody(method) ? body : null);

        try {
            Response response = httpClient.newCall(builder.build()).execute();
            writeProxyResponse(response, servletResponse);
        } catch (IOException ex) {
            writeUnavailableResponse(servletResponse, ex);
        }
    }

    private void writeProxyResponse(Response response, HttpServletResponse servletResponse) throws IOException {
        try (response) {
            servletResponse.setStatus(response.code());
            copyResponseHeaders(response, servletResponse);

            ResponseBody responseBody = response.body();
            try (InputStream inputStream = responseBody == null ? InputStream.nullInputStream() : responseBody.byteStream()) {
                inputStream.transferTo(servletResponse.getOutputStream());
                servletResponse.flushBuffer();
            }
        }
    }

    private void writeUnavailableResponse(HttpServletResponse servletResponse, IOException ex) {
        if (servletResponse.isCommitted()) {
            return;
        }
        servletResponse.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        servletResponse.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        byte[] payload = ("{\"message\":\"RAG service unavailable: " + sanitize(ex.getMessage()) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        try {
            servletResponse.getOutputStream().write(payload);
            servletResponse.flushBuffer();
        } catch (IOException ignored) {
            // The client has already disconnected.
        }
    }

    private void copyRequestHeaders(HttpServletRequest request, Request.Builder builder, boolean multipartRequest) {
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (shouldSkipRequestHeader(headerName, multipartRequest)) {
                continue;
            }
            Enumeration<String> headerValues = request.getHeaders(headerName);
            while (headerValues != null && headerValues.hasMoreElements()) {
                builder.addHeader(headerName, headerValues.nextElement());
            }
        }
    }

    private void copyResponseHeaders(Response response, HttpServletResponse servletResponse) {
        response.headers().forEach(pair -> {
            String headerName = pair.getFirst();
            if (shouldSkipResponseHeader(headerName)) {
                return;
            }
            servletResponse.addHeader(headerName, pair.getSecond());
        });
    }

    private RequestBody buildRequestBody(HttpServletRequest request, String method) {
        if (!permitsRequestBody(method)) {
            return null;
        }
        if (request instanceof MultipartHttpServletRequest multipartRequest) {
            return buildMultipartRequestBody(multipartRequest);
        }
        try {
            byte[] bytes = request.getInputStream().readAllBytes();
            MediaType mediaType = parseMediaType(request.getContentType());
            return RequestBody.create(bytes, mediaType);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read request body", ex);
        }
    }

    private RequestBody buildMultipartRequestBody(MultipartHttpServletRequest request) {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);

        request.getParameterMap().forEach((name, values) -> {
            if (values == null) {
                return;
            }
            for (String value : values) {
                builder.addFormDataPart(name, value == null ? "" : value);
            }
        });

        request.getMultiFileMap().forEach((name, files) -> {
            if (files == null) {
                return;
            }
            for (MultipartFile file : files) {
                addMultipartFile(builder, name, file);
            }
        });

        return builder.build();
    }

    private void addMultipartFile(MultipartBody.Builder builder, String name, MultipartFile file) {
        String filename = file.getOriginalFilename();
        MediaType mediaType = parseMediaType(file.getContentType());
        try {
            builder.addFormDataPart(
                    name,
                    filename == null || filename.isBlank() ? "file" : filename,
                    RequestBody.create(file.getBytes(), mediaType));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read multipart file: " + filename, ex);
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

    private boolean shouldSkipRequestHeader(String headerName, boolean multipartRequest) {
        if (headerName == null) {
            return true;
        }
        String normalized = headerName.trim().toLowerCase();
        return HEADER_HOST.equals(normalized)
                || HEADER_CONNECTION.equals(normalized)
                || HEADER_CONTENT_LENGTH.equals(normalized)
                || (multipartRequest && HEADER_CONTENT_TYPE.equals(normalized))
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
