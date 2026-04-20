package com.aerosync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AppConfig {

    @Value("${ml.service.url}")
    private String mlServiceUrl;

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    // Simple WebClient for ML service
    // No custom codec needed — MLService.java sends a Map directly
    // Map keys are already snake_case so Python gets correct field names
    @Bean(name = "mlWebClient")
    public WebClient mlWebClient() {
        return WebClient.builder()
                .baseUrl(mlServiceUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // WebClient for Gemini API
    @Bean(name = "geminiWebClient")
    public WebClient geminiWebClient() {
        return WebClient.builder()
                .baseUrl(geminiApiUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}