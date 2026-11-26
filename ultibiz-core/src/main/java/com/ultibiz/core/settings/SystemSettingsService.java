package com.ultibiz.core.settings;

import com.ultibiz.core.ai.AiSecretCipher;
import com.ultibiz.core.infrastructure.SystemSettingRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * The configuration of the deployment, editable by the administrator in the
 * interface (Administration -> System settings).
 *
 * The environment variable stays the fallback: the value of the database wins.
 * The secrets are sealed with the deployment key — they are never stored in
 * clear text and never sent back to the browser.
 *
 * The key that seals the secrets is itself a setting: it is stored sealed with
 * the key of the environment. When it is changed, the stored secrets are
 * sealed again with the new key.
 */
@Service
public class SystemSettingsService {

    public static final String SEALING_KEY = "ai.encryption.key";

    private static final Logger LOG = LoggerFactory.getLogger(SystemSettingsService.class);
    private static final long CACHE_MILLIS = 5000L;

    private final SystemSettingRepository repository;
    private final AiSecretCipher bootstrap;
    private final Environment env;
    private final Map<String, String> cache = new LinkedHashMap<>();
    private volatile long cacheValidUntil;
    private volatile AiSecretCipher cipher;

    public SystemSettingsService(SystemSettingRepository repository, AiSecretCipher cipher,
                                 Environment env) {
        this.repository = repository;
        this.bootstrap = cipher;
        this.env = env;
    }

    /** The key that seals the secrets: the stored one wins over the environment. */
    public String sealingKey() {
        String stored = repository.findValue(SEALING_KEY);
        if (stored != null && !stored.isBlank()) {
            String opened = bootstrap.decrypt(stored);
            if (opened != null && !opened.isBlank()) {
                return opened;
            }
        }
        String key = env.getProperty("ULTIBIZ_AI_ENCRYPTION_KEY");
        if (key == null || key.isBlank()) {
            key = env.getProperty("ultibiz.ai.encryption-key");
        }
        return key == null ? "" : key.trim();
    }

    private AiSecretCipher cipher() {
        AiSecretCipher current = this.cipher;
        if (current == null) {
            current = new AiSecretCipher(sealingKey());
            this.cipher = current;
        }
        return current;
    }

    /** The effective value of a setting: the database wins over the environment. */
    public String value(String key) {
        long now = System.currentTimeMillis();
        if (now > cacheValidUntil) {
            synchronized (cache) {
                if (now > cacheValidUntil) {
                    cache.clear();
                    cacheValidUntil = now + CACHE_MILLIS;
                }
            }
        }
        synchronized (cache) {
            String cached = cache.get(key);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
        }
        String resolved = resolve(key);
        synchronized (cache) {
            cache.put(key, resolved == null ? "" : resolved);
        }
        return resolved;
    }

    public int intValue(String key, int fallback) {
        String raw = value(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            LOG.warn("The setting {} is not a number: {}", key, raw);
            return fallback;
        }
    }

    public boolean boolValue(String key, boolean fallback) {
        String raw = value(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.equals("yes") || normalized.equals("on")
                || normalized.equals("1")) {
            return true;
        }
        if (normalized.equals("false") || normalized.equals("no") || normalized.equals("off")
                || normalized.equals("0")) {
            return false;
        }
        return fallback;
    }

    private String resolve(String key) {
        SettingDefinition definition = SettingDefinitions.byKey(key);
        if (definition == null) {
            return null;
        }
        String stored = repository.findValue(key);
        if (stored != null && !stored.isBlank()) {
            if (definition.secret()) {
                String opened = cipher().decrypt(stored);
                if (opened != null) {
                    return opened;
                }
            } else {
                return stored;
            }
        }
        String environment = definition.environmentVariable() == null ? null
                : env.getProperty(definition.environmentVariable());
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        return definition.defaultValue();
    }

    /** Whether a secret is stored (the form only says "set"). */
    public boolean hasSecret(String key) {
        return repository.hasSecret(key);
    }

    /** The value for the form: the secrets are never shown again. */
    public String formValue(String key) {
        SettingDefinition definition = SettingDefinitions.byKey(key);
        if (definition == null) {
            return "";
        }
        if (definition.secret()) {
            return "";
        }
        String value = value(key);
        return value == null ? "" : value;
    }

    /**
     * Writes the settings of the form. An empty field keeps the stored value,
     * a ticked box removes it. The names of the settings that need a restart
     * are returned.
     */
    public List<String> save(Map<String, String> values, Set<String> clear, String actor) {
        List<String> restart = new ArrayList<>();
        String previousKey = sealingKey();
        String newKey = previousKey;
        String submittedKey = values == null ? null : values.get(SEALING_KEY);
        if (submittedKey != null && !submittedKey.isBlank()) {
            newKey = submittedKey.trim();
        }
        AiSecretCipher oldCipher = cipher();
        for (SettingDefinition definition : SettingDefinitions.all()) {
            String key = definition.key();
            if (SEALING_KEY.equals(key)) {
                continue;
            }
            if (clear != null && clear.contains(key)) {
                repository.delete(key);
                continue;
            }
            String raw = values == null ? null : values.get(key);
            if (raw == null) {
                continue;
            }
            if (definition.type() == SettingDefinition.Type.NUMBER && !raw.isBlank()) {
                try {
                    Integer.parseInt(raw.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("The setting " + key + " is not a number");
                }
            }
            if (definition.secret()) {
                if (raw.isBlank()) {
                    continue;
                }
                String sealed = oldCipher.encrypt(raw);
                if (sealed == null) {
                    throw new IllegalStateException("The deployment key is missing — a secret can"
                            + " not be stored");
                }
                repository.save(key, sealed, true, actor);
            } else {
                repository.save(key, raw, false, actor);
            }
            if (definition.restartRequired()) {
                restart.add(key);
            }
        }
        if (!newKey.equals(previousKey)) {
            reseal(newKey, oldCipher, actor);
        }
        if (submittedKey != null && !submittedKey.isBlank()) {
            String sealedKey = bootstrap.encrypt(newKey);
            if (sealedKey == null) {
                throw new IllegalStateException("The deployment key of the environment is missing —"
                        + " the sealing key can not be stored");
            }
            repository.save(SEALING_KEY, sealedKey, true, actor);
            if (!restart.contains(SEALING_KEY)) {
                restart.add(SEALING_KEY);
            }
        }
        synchronized (cache) {
            cache.clear();
            cacheValidUntil = 0;
        }
        this.cipher = null;
        LOG.info("The system settings were changed by {}", actor);
        return restart;
    }

    /** Seals the stored secrets again with the new key. */
    private void reseal(String newKey, AiSecretCipher oldCipher, String actor) {
        AiSecretCipher newCipher = new AiSecretCipher(newKey);
        for (SettingDefinition definition : SettingDefinitions.all()) {
            if (!definition.secret() || SEALING_KEY.equals(definition.key())) {
                continue;
            }
            String stored = repository.findValue(definition.key());
            if (stored == null || stored.isBlank()) {
                continue;
            }
            String opened = oldCipher.decrypt(stored);
            if (opened == null) {
                LOG.warn("The secret {} can not be read with the old key", definition.key());
                continue;
            }
            String sealed = newCipher.encrypt(opened);
            if (sealed != null) {
                repository.save(definition.key(), sealed, true, actor);
            }
        }
    }
}
