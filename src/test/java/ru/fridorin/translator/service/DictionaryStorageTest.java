package ru.fridorin.translator.service;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fridorin.translator.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DictionaryStorageTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void reportsReplacementGroupsAndUsesJavaNumericGroupSemantics() {
        assertFalse(SkyblockDictionary.validReplacement("$2", 1, java.util.Set.of()));
        assertTrue(SkyblockDictionary.validReplacement("$20", 2, java.util.Set.of()));
        assertTrue(SkyblockDictionary.validReplacement("${amount}", 1, java.util.Set.of("amount")));
        assertFalse(SkyblockDictionary.validReplacement("${missing}", 1, java.util.Set.of("amount")));
        assertTrue(SkyblockDictionary.validReplacement("\\$2", 0, java.util.Set.of()));
    }

    @Test
    public void bundledDictionariesMeetTheDownloadValidationRules() throws IOException {
        for (String language : new String[] { "ru", "fr" }) {
            String path = "/assets/skyblock_translator/dictionary/dictionary_" + language + ".json";
            try (var stream = SkyblockDictionary.class.getResourceAsStream(path)) {
                var dictionary = SkyblockDictionary.parseEntries(AtomicFiles.readUtf8(stream, 1024 * 1024));
                assertTrue(dictionary.containsKey("strength"));
                assertTrue(dictionary.keySet().stream().anyMatch(key -> key.startsWith("r:")));
            }
        }
    }

    @Test
    public void rejectsInvalidShapesAndNormalizedDuplicateKeys() {
        assertThrows(IOException.class, () -> SkyblockDictionary.parseEntries("[]"));
        assertThrows(IOException.class, () -> SkyblockDictionary.parseEntries("{\"Strength\":42}"));
        assertThrows(IOException.class, () -> SkyblockDictionary.parseEntries("{\"Strength\":\"Force\",\"strength\":\"Puissance\"}"));
        assertThrows(IOException.class, () -> SkyblockDictionary.parseEntries("{\"Strength\":\"" + "a".repeat(8192) + "\"}"));
    }

    @Test
    public void addingAnEntryDoesNotOverwriteCorruptUserData() throws IOException {
        Path file = temporary.newFile("user_dictionary.json").toPath();
        String damaged = "{\"Keep this when recovering\": ";
        Files.writeString(file, damaged);

        assertFalse(SkyblockDictionary.addUserTranslation(file, "A test translation", "Тестовый перевод"));
        assertEquals(damaged, Files.readString(file));
        assertNull(SkyblockDictionary.getTranslationOrUserTranslation("A test translation"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void reloadsCustomCapturesAndPreservesProtectedNames() throws Exception {
        var userField = SkyblockDictionary.class.getDeclaredField("USER_DICTIONARY");
        userField.setAccessible(true);
        var userDictionary = (Map<String, String>) userField.get(null);
        var previous = Map.copyOf(userDictionary);
        var restore = SkyblockDictionary.class.getDeclaredMethod("replaceUserEntries", Map.class);
        restore.setAccessible(true);
        Path file = temporary.getRoot().toPath().resolve("user_dictionary.json");
        try {
            assertTrue(SkyblockDictionary.addUserTranslation(file, "Health points", "Очки здоровья"));
            assertTrue(SkyblockDictionary.addUserTranslation(file, "r:^(\\d+) qwerty$", "$1 очков"));
            assertEquals("12 очков", SkyblockDictionary.lookupRegex("12 qwerty"));
            assertTrue(SkyblockDictionary.addUserTranslation(file, "r:^broken (\\d+)$", "$2"));
            var inspection = SkyblockDictionary.inspect("12 qwerty");
            assertEquals("12 очков", inspection.translation());
            assertEquals(java.util.List.of(SkyblockDictionary.Source.USER), inspection.sources());
            assertEquals(SkyblockDictionary.InspectionStatus.MATCH, inspection.status());
            assertEquals(new SkyblockDictionary.RuleIssue("r:^broken (\\d+)$", "replacement", 1, ""),
                    inspection.ruleIssues().getFirst());
            assertNull(SkyblockDictionary.lookupRegex("1".repeat(2100) + " qwerty"));
            assertEquals("Очки здоровья", SkyblockDictionary.parseEntries(Files.readString(file)).get("health points"));
            assertEquals("{P17} Очки здоровья", SkyblockDictionary.lookup("{P17} Health points"));
            assertNull(SkyblockDictionary.lookup("Health {P17} points"));
        } finally {
            restore.invoke(null, previous);
        }
    }
}
