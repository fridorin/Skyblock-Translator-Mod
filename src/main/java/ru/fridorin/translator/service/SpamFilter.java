package ru.fridorin.translator.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SpamFilter {
    private static final Pattern PATTERN_CURRENCY = Pattern.compile("^\\+\\d+.*(Coins|Bits|XP|Gold|Silver|Motes|Gems|Farming|Mining|Combat|Foraging|Fishing|Enchanting|Alchemy|Taming|Carpentry|Runecrafting).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_DAMAGE = Pattern.compile("^[✧✦].*\\d+.*[✧✦]$");
    private static final Pattern PATTERN_NO_LETTERS = Pattern.compile("^[^a-zA-Z]+$");

    public static boolean shouldIgnore(String text) {
        if (text == null) return true;
        String trimmed = text.replaceAll("§x(?:§[0-9a-fA-F]){6}|§.", "").trim();
        if (trimmed.isEmpty()) return true;

        // Fast indexOf checks instead of regex backtracking for common patterns
        if (trimmed.contains("HP") && trimmed.contains("Defense") && trimmed.contains("Mana")) {
            int hp = trimmed.indexOf("HP");
            int def = trimmed.indexOf("Defense");
            int mana = trimmed.indexOf("Mana");
            if (hp < def && def < mana) return true;
        }

        if (trimmed.matches("\\[[|=+\\- ]+\\]")) return true;

        if (trimmed.startsWith("+") && PATTERN_CURRENCY.matcher(trimmed).matches()) return true;
        if (PATTERN_DAMAGE.matcher(trimmed).matches()) return true;
        if (PATTERN_NO_LETTERS.matcher(trimmed).matches()) return true;

        return false;
    }
}

