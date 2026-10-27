package ru.fridorin.translator;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;
import java.util.Map;
import java.util.Locale;
import java.lang.reflect.Field;
import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.service.SkyblockDictionary;
import ru.fridorin.translator.service.TranslationService;
import ru.fridorin.translator.cache.TranslationCache;

public class RegressionTest {
    @SuppressWarnings("unchecked")
    private Map<String, String> dictionary() throws Exception {
        Field field = SkyblockDictionary.class.getDeclaredField("DICTIONARY");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    @Before
    public void reset() throws Exception {
        dictionary().clear();
        TranslatorConfigManager.getConfig().targetLanguage = "ru";
        TranslatorConfigManager.getConfig().enabled = true;
        TranslatorConfigManager.getConfig().apiProvider = "GOOGLE_FREE";
        TranslatorConfigManager.getConfig().apiKey = "";
    }

    @Test
    public void formattedStatsTranslateWithoutApi() throws Exception {
        dictionary().put("strength", "Сила");
        String translated = TranslationService.getImmediateTranslation("§cStrength§7: §a+150");
        assertNotNull(translated);
        assertTrue(translated, translated.contains("Сила"));
        assertTrue(translated, translated.contains("+150"));
        assertFalse(translated, translated.contains("{F"));
    }

    @Test
    public void formattingInsideDictionaryPhraseDoesNotBlockTranslation() throws Exception {
        dictionary().put("critical damage", "Критический урон");
        String translated = TranslationService.getImmediateTranslation("§cCritical §6Damage");
        assertNotNull(translated);
        assertTrue(translated, translated.contains("Критический урон"));
    }

    @Test
    public void nestedBypassSurvivesInnerException() {
        assertFalse(TranslationService.isBypassed());
        TranslationService.runWithBypass(() -> {
            try {
                TranslationService.callWithBypass(() -> { throw new IllegalStateException(); });
                fail();
            } catch (IllegalStateException expected) { }
            assertTrue(TranslationService.isBypassed());
        });
        assertFalse(TranslationService.isBypassed());
    }

    @Test
    public void frenchTranslationsCanBeCachedInTurkishLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TranslatorConfigManager.getConfig().targetLanguage = "fr";
            TranslationCache.put("ITEM", "Objet");
            assertEquals("Objet", TranslationCache.get("item"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void invalidApiKeysDoNotLeakPermitsOrLeavePendingTranslations() throws Exception {
        Field permitsField = TranslationService.class.getDeclaredField("API_SEMAPHORE");
        permitsField.setAccessible(true);
        java.util.concurrent.Semaphore permits = (java.util.concurrent.Semaphore) permitsField.get(null);
        int before = permits.availablePermits();
        Field activeField = TranslationService.class.getDeclaredField("ACTIVE_TRANSLATIONS");
        activeField.setAccessible(true);
        for (String provider : java.util.List.of("DEEPL_FREE", "YANDEX")) {
            TranslatorConfigManager.getConfig().apiProvider = provider;
            TranslatorConfigManager.getConfig().apiKey = "invalid\nkey";
            for (int i = 0; i < 4; i++) {
                String result = TranslationService.translateAsync("Unlisted translation sentence " + provider + " " + (char) ('a' + i))
                        .get(1, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("", result);
            }
        }
        assertEquals(before, permits.availablePermits());
        assertTrue(((Map<?, ?>) activeField.get(null)).isEmpty());
    }
}
