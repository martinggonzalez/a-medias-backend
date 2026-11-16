package com.amedias.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String jwtSecret,
        int jwtTtlMinutes,
        List<String> frontendOrigins,
        Gemini gemini,
        Seed seed) {

    public record Gemini(String apiKey, String model) {}

    public record Seed(String adelaEmail, String adelaPassword, String martinEmail, String martinPassword) {}
}
