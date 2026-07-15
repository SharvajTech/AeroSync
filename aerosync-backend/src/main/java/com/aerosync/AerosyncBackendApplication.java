package com.aerosync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AerosyncBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(
            AerosyncBackendApplication.class, args);
    }
}