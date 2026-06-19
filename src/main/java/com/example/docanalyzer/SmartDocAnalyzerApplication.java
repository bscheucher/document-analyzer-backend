package com.example.docanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
public class SmartDocAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartDocAnalyzerApplication.class, args);
    }
}
