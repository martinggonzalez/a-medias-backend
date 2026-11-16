package com.amedias;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AMediasApplication {
    public static void main(String[] args) {
        SpringApplication.run(AMediasApplication.class, args);
    }
}
