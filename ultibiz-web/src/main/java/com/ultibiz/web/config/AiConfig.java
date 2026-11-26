package com.ultibiz.web.config;

import com.ultibiz.core.ai.AiSecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of the AI assistant that need a deployment value. */
@Configuration
public class AiConfig {

    /**
     * The cipher of the API keys. Without the deployment key the cipher is
     * inactive and no key can be stored — a key is never written in clear text.
     */
    @Bean
    AiSecretCipher aiSecretCipher(
            @Value("${ultibiz.ai.encryption-key:${ULTIBIZ_AI_ENCRYPTION_KEY:}}") String key) {
        return new AiSecretCipher(key);
    }
}
