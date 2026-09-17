package com.aesoftwaresolutions.solid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the Solid application. Each sub-package is a Spring Modulith module. */
@SpringBootApplication
public class SolidApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolidApplication.class, args);
    }
}
