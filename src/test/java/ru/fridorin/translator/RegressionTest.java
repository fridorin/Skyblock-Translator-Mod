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
        Field config = TranslatorConfigManager.class.getDeclaredField("config");
        config.setAccessible(true);
        config.set(null, new ru.fridorin.translator.config.TranslatorConfig());
        Field cacheLanguage = TranslationCache.class.getDeclaredField("activeLanguage");
        cacheLanguage.setAccessible(true);
        cacheLanguage.set(null, "ru");
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
            try {
                Field language = TranslationCache.class.getDeclaredField("activeLanguage");
                language.setAccessible(true);
                language.set(null, "fr");
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            TranslationCache.put("ITEM", "Objet");
            assertEquals("Objet", TranslationCache.get("item"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void numbersNeverCorruptProtectedPlaceholders() {
        var result = TranslationService.extractTemplates("{F12}Strength: +150 {P2} / {C0} 3%");
        assertEquals("{F12}Strength: {0} {P2} / {C0} {1}", result.templatedText);
        assertEquals(java.util.List.of("+150", "3%"), result.values);
        assertEquals("{F12}Strength: +150 {P2} / {C0} 3%", TranslationService.restoreTemplates(result.templatedText, result.values));
    }

    @Test
    public void unicodeTranslationsAreComparedWithoutLosingLetters() {
        assertFalse(TranslationService.isTranslationSame("Сила", "Защита"));
        assertFalse(TranslationService.isTranslationSame("é", "è"));
        assertTrue(TranslationService.isTranslationSame("§cСила: +150", "Сила +150"));
    }

    @Test
    public void hypixelHostCheckUsesDomainBoundaries() {
        assertTrue(TranslatorModClient.isHypixelAddress("MC.HYPIXEL.NET:25565"));
        assertTrue(TranslatorModClient.isHypixelAddress("hypixel.io."));
        assertFalse(TranslatorModClient.isHypixelAddress("hypixel.net.example.com"));
        assertFalse(TranslatorModClient.isHypixelAddress("nothypixel.net"));
        assertFalse(TranslatorModClient.isHypixelAddress(null));
    }

    @Test
    public void invalidConfigValuesAreNormalized() {
        var config = TranslatorConfigManager.getConfig();
        config.targetLanguage = "../../outside";
        config.apiProvider = null;
        config.chatDisplayMode = "invalid";
        config.apiKey = null;
        config.translateTooltips = false;
        TranslatorConfigManager.validate(config);
        assertEquals("ru", config.targetLanguage);
        assertEquals("GOOGLE_FREE", config.apiProvider);
        assertEquals("SEPARATE_LINE", config.chatDisplayMode);
        assertEquals("", config.apiKey);
        assertFalse(config.translateTooltips);
        assertEquals("zh-cn", TranslatorConfigManager.normalizeLanguage(" ZH-CN "));
    }

    @Test
    public void staleTranslationsCannotRepopulateClearedCache() {
        long generation = TranslatorModClient.getCacheGeneration();
        TranslatorModClient.clearComponentCache();
        TranslationCache.put("Stale phrase", "Старая фраза", "ru", generation);
        assertNull(TranslationCache.get("Stale phrase"));
        TranslationCache.put("Fresh phrase", "Новая фраза", "ru", TranslatorModClient.getCacheGeneration());
        assertEquals("Новая фраза", TranslationCache.get("Fresh phrase"));
        TranslatorConfigManager.getConfig().targetLanguage = "fr";
        assertNull(TranslationCache.get("Fresh phrase"));
    }

    @Test
    public void tooltipSwitchesControlDescriptionSectionsAndRarity() {
        var config = TranslatorConfigManager.getConfig();
        var filter = new ru.fridorin.translator.service.TooltipTranslationFilter();
        config.translateAbilityNames = false;
        config.translateAbilityDescriptions = false;
        config.translateEnchantmentDescriptions = false;
        config.translateRarity = false;
        assertFalse(filter.shouldTranslate("Item Ability: Test RIGHT CLICK", 1, config));
        assertFalse(filter.shouldTranslate("Deals damage", 2, config));
        assertFalse(filter.shouldTranslate("", 3, config));
        assertTrue(filter.shouldTranslate("Strength: +150", 4, config));
        assertTrue(filter.shouldTranslate("Sharpness V", 5, config));
        assertFalse(filter.shouldTranslate("Increases melee damage", 6, config));
        assertFalse(filter.shouldTranslate("LEGENDARY DUNGEON SWORD", 7, config));
    }

    @Test
    public void badRegexReplacementDoesNotCrashRendering() throws Exception {
        dictionary().put("r:(Strength)", "$9");
        var rebuild = SkyblockDictionary.class.getDeclaredMethod("rebuildRegexCache");
        rebuild.setAccessible(true);
        rebuild.invoke(null);
        assertNull(SkyblockDictionary.lookupRegex("Strength"));
        assertNull(SkyblockDictionary.lookupRegex("Strength"));
        var normalize = SkyblockDictionary.class.getDeclaredMethod("normalizeDictionaryKey", String.class);
        normalize.setAccessible(true);
        assertEquals("r:\\D+", normalize.invoke(null, "r:\\D+"));
    }

    @Test
    public void validTextAfterProgressBarIsNotDiscarded() {
        assertTrue(ru.fridorin.translator.service.SpamFilter.shouldIgnore("§a[||||  ]"));
        assertFalse(ru.fridorin.translator.service.SpamFilter.shouldIgnore("[---] Quest complete"));
    }

    @Test
    public void inheritedComponentStylesSurviveSerialization() {
        var original = net.minecraft.network.chat.Component.literal("Parent ")
                .withStyle(net.minecraft.ChatFormatting.RED)
                .append(net.minecraft.network.chat.Component.literal("Child"));
        String legacy = TranslatorModClient.componentToLegacy(original);
        assertEquals(2, legacy.split("§c", -1).length - 1);
        assertEquals("Parent Child", TranslatorModClient.legacyToComponent(legacy).getString());
    }

    @Test
    public void atomicWritesReplaceWholeFilesWithoutLeavingTemporaryFiles() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("translator-atomic-test");
        java.nio.file.Path file = directory.resolve("config.json");
        try {
            ru.fridorin.translator.util.AtomicFiles.write(file, "{\"value\":\"Сила\"}");
            ru.fridorin.translator.util.AtomicFiles.write(file, "{\"value\":\"Force\"}");
            assertEquals("{\"value\":\"Force\"}", java.nio.file.Files.readString(file));
            try (var files = java.nio.file.Files.list(directory)) { assertEquals(1, files.count()); }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void userPhrasesOverrideGlobalPhrases() throws Exception {
        Field userField = SkyblockDictionary.class.getDeclaredField("USER_DICTIONARY");
        userField.setAccessible(true);
        Map<String, String> user = (Map<String, String>) userField.get(null);
        var rebuild = SkyblockDictionary.class.getDeclaredMethod("rebuildRegexCache");
        rebuild.setAccessible(true);
        try {
            dictionary().put("strength", "Сила");
            user.put("strength", "Мощь");
            rebuild.invoke(null);
            assertEquals("Мощь +150", SkyblockDictionary.translateByPhrases("Strength +150"));
        } finally {
            user.clear();
            rebuild.invoke(null);
        }
    }

    @Test
    public void technicalFiltersRespectTheirSwitches() {
        var config = TranslatorConfigManager.getConfig();
        assertTrue(TranslationService.isTechnicalOrModLine("Cooldown: 5 seconds"));
        config.ignoreAbilityCooldowns = false;
        assertFalse(TranslationService.isTechnicalOrModLine("Cooldown: 5 seconds"));
        assertTrue(TranslationService.isTechnicalOrModLine("Click to view recipes"));
        config.ignoreClickActions = false;
        assertFalse(TranslationService.isTechnicalOrModLine("Click to view recipes"));
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
