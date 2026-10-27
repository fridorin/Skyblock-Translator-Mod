package ru.fridorin.translator.service;

import java.util.Locale;
import java.util.regex.Pattern;
import ru.fridorin.translator.config.TranslatorConfig;

/** Tracks the standard blank-line-separated lore sections used by SkyBlock. */
public final class TooltipTranslationFilter {
    private static final Pattern RARITY = Pattern.compile("^[^A-Za-z]*(?:VERY SPECIAL|SPECIAL|COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE)\\b.*");
    private boolean abilityDescription;
    private boolean enchantmentDescription;

    public boolean shouldTranslate(String text, int index, TranslatorConfig config) {
        String clean = text.replaceAll("§x(?:§[0-9a-fA-F]){6}|§.", "").trim();
        if (clean.isEmpty()) {
            abilityDescription = false;
            enchantmentDescription = false;
            return false;
        }
        if (index == 0) return config.translateItemNames;
        if (RARITY.matcher(clean).matches()) return config.translateRarity;
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ability:") || lower.startsWith("item ability:")) {
            abilityDescription = true;
            enchantmentDescription = false;
            return config.translateAbilityNames;
        }
        if (TranslationService.ENCHANTMENT_LINE_PATTERN.matcher(clean).matches()) {
            enchantmentDescription = true;
            abilityDescription = false;
            return config.translateEnchantments;
        }
        if (abilityDescription && !config.translateAbilityDescriptions) return false;
        if (enchantmentDescription && !config.translateEnchantmentDescriptions) return false;
        return !TranslationService.NAMESPACE_PATTERN.matcher(clean).matches();
    }
}
