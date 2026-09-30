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
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return true;

        // Fast indexOf checks instead of regex backtracking for common patterns
        if (trimmed.contains("HP") && trimmed.contains("Defense") && trimmed.contains("Mana")) {
            int hp = trimmed.indexOf("HP");
            int def = trimmed.indexOf("Defense");
            int mana = trimmed.indexOf("Mana");
            if (hp < def && def < mana) return true;
        }

        if (trimmed.contains("[")) {
            // Check for progress bar-like spam (e.g. [||||||||||||||          ])
            boolean isProgressBar = true;
            boolean hasChars = false;
            for (int i = trimmed.indexOf('[') + 1; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == ']') break;
                if (c != '|' && c != '=' && c != '+' && c != '-' && c != ' ') {
                    isProgressBar = false;
                    break;
                }
                hasChars = true;
            }
            if (isProgressBar && hasChars) return true;
        }

        if (trimmed.startsWith("+") && PATTERN_CURRENCY.matcher(trimmed).matches()) return true;
        if (PATTERN_DAMAGE.matcher(trimmed).matches()) return true;
        if (PATTERN_NO_LETTERS.matcher(trimmed).matches()) return true;

        return false;
    }
}

