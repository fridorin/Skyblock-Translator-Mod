package ru.fridorin.translator.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fridorin.translator.TranslatorModClient;
import ru.fridorin.translator.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class TranslatorConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranslatorConfigManager.class);
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();
    private static final int MAX_CONFIG_BYTES = 64 * 1024;
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(?:-[a-z0-9]{2,8})*");
    private static final Set<String> PROVIDERS = Set.of("GOOGLE_FREE", "DEEPL_FREE", "YANDEX");
    private static final Set<String> DISPLAY_MODES = Set.of("SEPARATE_LINE", "APPEND", "INLINE", "REPLACE");
    private static Path configFile;
    private static volatile TranslatorConfig config = new TranslatorConfig();

    public static String normalizeLanguage(String value) {
        if (value == null) return "ru";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 63 && LANGUAGE.matcher(normalized).matches() ? normalized : "ru";
    }

    public static void validate(TranslatorConfig value) {
        value.targetLanguage = normalizeLanguage(value.targetLanguage);
        value.apiProvider = normalizeOption(value.apiProvider, PROVIDERS, "GOOGLE_FREE");
        if (value.deeplApiKey == null) value.deeplApiKey = "";
        if (value.yandexApiKey == null) value.yandexApiKey = "";
        if (value.legacyApiKey != null && !value.legacyApiKey.isBlank()) {
            if ("DEEPL_FREE".equals(value.apiProvider) && value.deeplApiKey.isBlank()) {
                value.deeplApiKey = value.legacyApiKey;
            } else if ("YANDEX".equals(value.apiProvider) && value.yandexApiKey.isBlank()) {
                value.yandexApiKey = value.legacyApiKey;
            } else if (!value.legacyApiKey.equals(value.keyFor(value.apiProvider))) {
                value.unassignedLegacyApiKey = value.legacyApiKey;
            }
            value.legacyApiKey = null;
        }
        value.configVersion = Math.max(value.configVersion, 3);
        value.chatDisplayMode = normalizeOption(value.chatDisplayMode, DISPLAY_MODES, "SEPARATE_LINE");
    }

    private static String normalizeOption(String option, Set<String> values, String fallback) {
        if (option == null) return fallback;
        String normalized = option.trim().toUpperCase(Locale.ROOT);
        return values.contains(normalized) ? normalized : fallback;
    }

    public static synchronized void init() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("skyblock_translator/config.json");
        load();
    }

    public static TranslatorConfig getConfig() {
        return config;
    }

    public static synchronized void load() {
        if (configFile == null) {
            return;
        }
        if (!Files.exists(configFile)) {
            save();
            return;
        }
        try {
            TranslatorConfig loaded = GSON.fromJson(AtomicFiles.read(configFile, MAX_CONFIG_BYTES), TranslatorConfig.class);
            if (loaded == null) throw new IOException("Expected a configuration object");
            int previousVersion = loaded.configVersion;
            String legacyKey = loaded.legacyApiKey;
            validate(loaded);
            config = loaded;
            TranslatorModClient.clearComponentCache();

            if (previousVersion != loaded.configVersion || !java.util.Objects.equals(legacyKey, loaded.legacyApiKey)) {
                save();
            }
        } catch (IOException | JsonParseException error) {
            LOGGER.warn("Could not load translator configuration; keeping the current settings");
        }
    }

    public static synchronized void save() {
        TranslatorConfig current = config;
        validate(current);
        TranslatorModClient.clearComponentCache();
        ru.fridorin.translator.cache.TranslationCache.refreshPersistence();
        if (configFile == null) {
            return;
        }
        try {
            AtomicFiles.write(configFile, GSON.toJson(current), MAX_CONFIG_BYTES);
        } catch (IOException error) {
            LOGGER.warn("Could not save translator configuration");
        }
    }
}
