package com.ultibiz.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * UltiBiz application entry point. Extends {@link SpringBootServletInitializer}
 * so the packaged WAR boots on an external Apache Tomcat 11, while
 * {@code java -jar} still works for local development.
 */
@SpringBootApplication(scanBasePackages = "com.ultibiz")
public class UltiBizApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(UltiBizApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(UltiBizApplication.class, args);
    }
}
