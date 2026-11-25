package com.ultibiz.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the scheduled jobs (the exchange retry batch). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
