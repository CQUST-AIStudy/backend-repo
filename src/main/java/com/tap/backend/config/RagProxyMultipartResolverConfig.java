package com.tap.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

@Configuration
public class RagProxyMultipartResolverConfig {

    @Bean(name = "multipartResolver")
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override
            public boolean isMultipart(HttpServletRequest request) {
                String requestUri = request == null ? "" : request.getRequestURI();
                if (requestUri != null && requestUri.startsWith("/rag/")) {
                    return false;
                }
                return super.isMultipart(request);
            }
        };
    }
}
