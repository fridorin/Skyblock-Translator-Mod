package ru.fridorin.translator.cache;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fridorin.translator.TranslatorModClient;
import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.service.TranslationScope;

import static org.junit.Assert.*;

public class CacheStorageTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void providerScopesExpirationAndChatDiskOptInSurviveReload() throws Exception {
        var directory = temporary.getRoot().toPath();
        var legacy = directory.resolve("cache_fr.json");
        var disk = directory.resolve("cache_v2_fr.json");
        Files.writeString(legacy, "{\"legacy\":\"Ancien\"}");
        Files.writeString(disk, """
                {"GOOGLE_FREE:WORLD:expired":{"text":"Périmé","provider":"GOOGLE_FREE",
                "scope":"WORLD","expiresAt":%d,"persistable":true}}
                """.formatted(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)));
        var config = TranslatorConfigManager.getConfig();
        String language = config.targetLanguage;
        String provider = config.apiProvider;
        boolean persistChat = config.persistChatCache;
        var languageField = TranslationCache.class.getDeclaredField("activeLanguage");
        languageField.setAccessible(true);
        var fileField = TranslationCache.class.getDeclaredField("cacheFile");
        fileField.setAccessible(true);
        var oldLanguage = languageField.get(null);
        var oldFile = fileField.get(null);
        try {
            config.targetLanguage = "fr";
            config.apiProvider = "GOOGLE_FREE";
            config.persistChatCache = false;
            TranslationCache.loadForLanguage(directory, "fr");
            assertNull(TranslationCache.get("expired", TranslationScope.WORLD));
            assertNull(TranslationCache.get("legacy", TranslationScope.WORLD));
            assertTrue(Files.exists(legacy));

            store("settings", "Paramètres", "GOOGLE_FREE", TranslationScope.WORLD);
            store("settings", "Réglages", "DEEPL_FREE", TranslationScope.WORLD);
            store("settings", "Préférences", "GOOGLE_FREE", TranslationScope.CHAT);
            assertEquals("Paramètres", TranslationCache.get("settings", TranslationScope.WORLD));
            assertEquals("Préférences", TranslationCache.get("settings", TranslationScope.CHAT));
            config.apiProvider = "DEEPL_FREE";
            assertEquals("Réglages", TranslationCache.get("settings", TranslationScope.WORLD));
            assertNull(TranslationCache.get("settings", TranslationScope.CHAT));

            config.apiProvider = "GOOGLE_FREE";
            config.persistChatCache = true;
            store("opted in", "Permis", "GOOGLE_FREE", TranslationScope.CHAT);
            TranslationCache.loadForLanguage(directory, "fr");
            assertFalse(Files.readString(disk).contains("Préférences"));
            assertEquals("Permis", TranslationCache.get("opted in", TranslationScope.CHAT));
            assertEquals("Paramètres", TranslationCache.get("settings", TranslationScope.WORLD));

            config.persistChatCache = false;
            TranslationCache.refreshPersistence();
            TranslationCache.loadForLanguage(directory, "fr");
            assertFalse(Files.readString(disk).contains("Permis"));
            assertNull(TranslationCache.get("opted in", TranslationScope.CHAT));

            config.targetLanguage = "ru";
            assertNull(TranslationCache.get("settings", TranslationScope.WORLD));
            config.targetLanguage = "fr";
            TranslationCache.clear();
            TranslationCache.loadForLanguage(directory, "fr");
            assertNull(TranslationCache.get("settings", TranslationScope.WORLD));
            assertEquals("{}", Files.readString(disk));
        } finally {
            config.targetLanguage = language;
            config.apiProvider = provider;
            config.persistChatCache = persistChat;
            languageField.set(null, oldLanguage);
            fileField.set(null, oldFile);
        }
    }

    private static void store(String source, String result, String provider, TranslationScope scope) {
        TranslationCache.put(source, result, "fr", TranslatorModClient.getCacheGeneration(), provider, scope);
    }
}
