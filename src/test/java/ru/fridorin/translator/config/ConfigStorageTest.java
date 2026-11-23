package ru.fridorin.translator.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ConfigStorageTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void sensitiveChatSettingsRequireOptInOnFreshInstall() {
        var defaults = new TranslatorConfig();
        assertFalse(defaults.translateDirectMessages);
        assertFalse(defaults.translateOutgoingChat);
    }

    @Test
    public void unassignedLegacyKeyIsNeverSentToAnotherProvider() {
        var config = new TranslatorConfig();
        config.legacyApiKey = "unassigned";
        TranslatorConfigManager.validate(config);
        config.apiProvider = "YANDEX";
        TranslatorConfigManager.validate(config);
        assertEquals("", config.keyFor("YANDEX"));
        assertEquals("unassigned", config.unassignedLegacyApiKey);
    }

    @Test
    public void migrationPreservesOptInAndInvalidReloadPreservesCurrentSettings() throws Exception {
        var file = temporary.newFile("config.json").toPath();
        var configFileField = TranslatorConfigManager.class.getDeclaredField("configFile");
        configFileField.setAccessible(true);
        var configField = TranslatorConfigManager.class.getDeclaredField("config");
        configField.setAccessible(true);
        var previousFile = configFileField.get(null);
        var previousConfig = configField.get(null);
        try {
            configFileField.set(null, file);
            Files.writeString(file, """
                {
                  "configVersion": 1,
                  "apiProvider": "DEEPL_FREE",
                  "apiKey": "legacy-key",
                  "translateDirectMessages": true,
                  "translateOutgoingChat": true,
                  "translateTooltips": false
                }
                """);
            TranslatorConfigManager.load();
            var loaded = TranslatorConfigManager.getConfig();
            assertEquals(3, loaded.configVersion);
            assertEquals("legacy-key", loaded.keyFor("DEEPL_FREE"));
            assertEquals("", loaded.keyFor("YANDEX"));
            assertFalse(Files.readString(file).contains("\"apiKey\""));
            assertTrue(loaded.translateDirectMessages);
            assertTrue(loaded.translateOutgoingChat);
            assertFalse(loaded.translateTooltips);

            String invalid = " ".repeat(70 * 1024);
            Files.writeString(file, invalid);
            TranslatorConfigManager.load();
            assertSame(loaded, TranslatorConfigManager.getConfig());
            assertEquals(invalid, Files.readString(file));
        } finally {
            configFileField.set(null, previousFile);
            configField.set(null, previousConfig);
        }
    }
}
