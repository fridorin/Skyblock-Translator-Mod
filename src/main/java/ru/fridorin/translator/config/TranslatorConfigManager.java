package ru.fridorin.translator.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.File;
import java.io.IOException;

public class TranslatorConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;
    private static volatile TranslatorConfig config = new TranslatorConfig();

    public static String normalizeLanguage(String value) {
        if (value == null) return "ru";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*") ? normalized : "ru";
    }

    public static void validate(TranslatorConfig value) {
        value.targetLanguage = normalizeLanguage(value.targetLanguage);
        if (value.apiProvider == null || !java.util.Set.of("GOOGLE_FREE", "DEEPL_FREE", "YANDEX")
                .contains(value.apiProvider.toUpperCase(java.util.Locale.ROOT))) value.apiProvider = "GOOGLE_FREE";
        value.apiProvider = value.apiProvider.toUpperCase(java.util.Locale.ROOT);
        if (value.apiKey == null) value.apiKey = "";
        if (value.chatDisplayMode == null || !java.util.Set.of("SEPARATE_LINE", "APPEND", "INLINE", "REPLACE")
                .contains(value.chatDisplayMode.toUpperCase(java.util.Locale.ROOT))) value.chatDisplayMode = "SEPARATE_LINE";
        value.chatDisplayMode = value.chatDisplayMode.toUpperCase(java.util.Locale.ROOT);
    }

    public static void init() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("skyblock_translator/config.json").toFile();
        load();
    }

    public static TranslatorConfig getConfig() {
        return config;
    }

    public static void load() {
        if (configFile == null) {
            return;
        }
        if (!configFile.exists()) {
            save();
            return;
        }
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(configFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            TranslatorConfig loaded = GSON.fromJson(reader, TranslatorConfig.class);
            if (loaded != null) {
                validate(loaded);
                config = loaded;
                
                // Keep user-selected switches when migrating older configurations.
                if (config.configVersion < 2) {
                    config.configVersion = 2;
                    save();
                }
            }
        } catch (Exception e) {
            System.err.println("[Skyblock Translator] Failed to load config: " + e.getMessage());
        }
    }

    public static void save() {
        validate(config);
        ru.fridorin.translator.TranslatorModClient.clearComponentCache();
        if (configFile == null) {
            return;
        }
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            ru.fridorin.translator.util.AtomicFiles.write(configFile.toPath(), GSON.toJson(config));
        } catch (IOException e) {
            System.err.println("[Skyblock Translator] Failed to save config: " + e.getMessage());
        }
    }
}
