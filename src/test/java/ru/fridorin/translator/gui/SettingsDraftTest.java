package ru.fridorin.translator.gui;

import org.junit.Test;
import ru.fridorin.translator.config.TranslatorConfig;

import static org.junit.Assert.*;

public class SettingsDraftTest {
    @Test
    public void languageSelectionStaysPendingUntilYaclAppliesIt() {
        var config = new TranslatorConfig();
        var language = TranslatorConfigScreen.languageOption(config);
        language.requestSet("Français (French)");
        assertEquals("ru", config.targetLanguage);
        assertTrue(language.changed());
        assertTrue(language.applyValue());
        assertEquals("fr", config.targetLanguage);
        assertFalse(language.changed());
    }
}
