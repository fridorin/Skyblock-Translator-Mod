package ru.fridorin.translator.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fridorin.translator.config.TranslatorConfig;
import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.util.AtomicFiles;
import ru.fridorin.translator.util.BoundedHttpBody;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static ru.fridorin.translator.service.TranslationService.HTTP_CLIENT;

public class SkyblockDictionary {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkyblockDictionary.class);
    private static final int MAX_DICTIONARY_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_REGEX_ENTRIES = 128;
    private static final int MAX_KEY_LENGTH = 512;
    private static final int MAX_VALUE_LENGTH = 2048;
    private static final int MAX_REGEX_INPUT = 2048;
    private static final int MAX_REGEX_CHAR_READS = 24_000;
    private static final int MAX_TRANSLATED_LENGTH = 8192;
    private static final Set<String> DOWNLOAD_LANGUAGES = Set.of("ru", "fr");
    private static final Pattern LEGACY_STYLE = Pattern.compile("§x(?:§[0-9a-fA-F]){6}|§.");
    private static final Pattern STRIPPED_FORMAT = Pattern.compile(LEGACY_STYLE.pattern() + "|(?i:\\{[FP]\\d+\\})");
    private static final Pattern FORMAT_OR_WHITESPACE = Pattern.compile(STRIPPED_FORMAT.pattern() + "|\\s+");
    private static final Pattern PLAYER_PLACEHOLDER = Pattern.compile("(?i)\\{P\\d+\\}");
    private static final Pattern ENGLISH_WORD = Pattern.compile("[a-zA-Z]+");
    private static final Pattern HYBRID_PLACEHOLDER = Pattern.compile("(?i)\\{[FPC]?\\d+\\}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NPC_TEXT = Pattern.compile("[^a-z\\s]");
    private static final Pattern REQUIREMENT_LEVEL = Pattern.compile(" level ", Pattern.CASE_INSENSITIVE);
    private static final Object FILE_LOCK = new Object();
    private static final Object DOWNLOAD_LOCK = new Object();
    private static final Map<String, String> DICTIONARY = new ConcurrentHashMap<>();
    private static final Map<String, String> USER_DICTIONARY = new ConcurrentHashMap<>();
    private static final Map<String, String> ITEM_TYPES = new ConcurrentHashMap<>();
    private static final Map<String, String> ITEM_TYPES_GENDER = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile String activeLanguage = "ru";
    private static CompletableFuture<Integer> pendingDownload;
    private static CompletableFuture<HttpResponse<String>> pendingResponse;
    private static long dictionaryGeneration;

    public static final java.util.Set<String> DEFAULT_NPC_NAMES = java.util.Set.of(
        "jerry", "kat", "elizabeth", "maddox", "ophelia", "malik", "mort", "anita", "jacob", "taylor", 
        "george", "tia", "rusty", "vanessa", "sirius", "shaman", "zog", "odger", "horace", "jax", 
        "toby", "elise", "diana", "marina", "paul", "cole", "foxy", "aatrox", "barry", "diaz", 
        "scorpius", "derpy", "walter", "rhys", "guber", "seymour", "lucius", "marco", "dusk", 
        "lynn", "lucia", "grog", "bulvar", "gwendolyn", "jotra", "bubu", "katrina", "vargul", 
        "tarn", "nema", "nika", "ruber", "kruger", "bort", "kate", "alphonso", "duchess", 
        "king", "queen", "emperor", "mayor", "minister", "plumber joe", "blacksmith", 
        "lapis miner", "iron forger", "gold forger", "guild master", "adventurer", "bartender", 
        "pat", "sven", "dust", "lumberjack", "alchemist", "wind glem", "josh", "amy", "luna", 
        "plumber", "builder", "wool weaver", "fish merchant", "artist", "banker", "bazaar", 
        "master tactician", "claudio"
    );

    public static final java.util.Set<String> DEFAULT_ALLOWED_ENGLISH_WORDS = java.util.Set.of(
        "xp", "hp", "hotm", "npc", "vip", "mvp", "api", "bin", "lvl", "uuid", "id", "coop", "neu", "sba", "v",
        "i", "ii", "iii", "iv", "vi", "vii", "viii", "ix", "x", "xi", "xii", "xiii", "xiv", "xv", "xvi", "xvii", "xviii", "xix", "xx",
        "skyblock", "hypixel", "dungeon", "dungeons"
    );

    private static volatile Set<String> dynamicNpcNames = Set.of();
    private static volatile Set<String> dynamicAllowedWords = Set.of();

    public static boolean isNpcName(String text) {
        if (text == null) return false;
        String clean = LEGACY_STYLE.matcher(text).replaceAll("").toLowerCase(java.util.Locale.ROOT);
        clean = NPC_TEXT.matcher(clean).replaceAll("").trim();
        if (clean.isEmpty()) return false;
        Set<String> names = dynamicNpcNames;
        if (DEFAULT_NPC_NAMES.contains(clean) || names.contains(clean)) return true;
        
        String[] words = WHITESPACE.split(clean);
        if (words.length <= 2) {
            for (String word : words) {
                if (DEFAULT_NPC_NAMES.contains(word) || names.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void updateDynamicLists() {
        dynamicNpcNames = wordsFor("npc_names_list");
        dynamicAllowedWords = wordsFor("allowed_english_words_list");
    }

    private static Set<String> wordsFor(String key) {
        var words = new HashSet<String>();
        for (String value : new String[] { DICTIONARY.get(key), USER_DICTIONARY.get(key) }) {
            if (value == null) continue;
            for (String entry : value.split(",")) {
                String word = entry.trim().toLowerCase(java.util.Locale.ROOT);
                if (!word.isEmpty()) words.add(word);
            }
        }
        return Set.copyOf(words);
    }

    public static String getVal(String key) {
        if (key == null) return null;
        return DICTIONARY.get(key.toLowerCase(java.util.Locale.ROOT));
    }

    private static String getRarityTranslation(String rarity, String gender) {
        boolean isVery = rarity.startsWith("VERY ");
        String baseRarity = isVery ? rarity.substring(5) : rarity;
        
        String genderKey = "rarity." + baseRarity.toLowerCase(java.util.Locale.ROOT) + "." + (gender != null ? gender.toLowerCase(java.util.Locale.ROOT) : "m");
        String translation = DICTIONARY.get(genderKey);
        
        if (translation == null) {
            translation = DICTIONARY.get("rarity." + baseRarity.toLowerCase(java.util.Locale.ROOT));
        }
        if (translation == null) {
            translation = DICTIONARY.get(baseRarity.toLowerCase(java.util.Locale.ROOT));
        }
        if (translation == null) {
            translation = baseRarity;
        }

        String prefix = "";
        if (isVery) {
            String veryTrans = DICTIONARY.get("very");
            if (veryTrans == null) {
                veryTrans = "VERY";
            }
            prefix = veryTrans.toUpperCase(java.util.Locale.ROOT) + " ";
        }

        return prefix + translation.toUpperCase(java.util.Locale.ROOT);
    }

    public static String cleanPlaceholders(String text) {
        if (text == null) return "";
        return WHITESPACE.matcher(STRIPPED_FORMAT.matcher(text).replaceAll("")).replaceAll(" ").trim();
    }

    private static String restoreLookupFormat(String original, String cleanText, String translated) {
        if (original == null || cleanText == null || cleanText.isEmpty()) return original;
        for (int index = 0; index <= original.length() - cleanText.length(); index++) {
            if (original.regionMatches(true, index, cleanText, 0, cleanText.length())) {
                return original.substring(0, index) + translated + original.substring(index + cleanText.length());
            }
        }
        Matcher tokens = FORMAT_OR_WHITESPACE.matcher(original);
        int contentStart = 0;
        int contentEnd = original.length();
        int previousTokenEnd = -1;
        while (tokens.find()) {
            if (tokens.start() == contentStart) contentStart = tokens.end();
            if (tokens.start() != previousTokenEnd) contentEnd = tokens.start();
            previousTokenEnd = tokens.end();
        }
        if (previousTokenEnd != original.length()) contentEnd = original.length();
        contentEnd = Math.max(contentStart, contentEnd);
        if (PLAYER_PLACEHOLDER.matcher(original).region(contentStart, contentEnd).find()) return null;
        return original.substring(0, contentStart) + translated + original.substring(contentEnd);
    }

    private static String getWordBoundaryRegex(String key) {
        StringBuilder sb = new StringBuilder();
        if (key.length() > 0 && Character.isLetterOrDigit(key.charAt(0))) {
            sb.append("\\b");
        }
        sb.append(java.util.regex.Pattern.quote(key));
        if (key.length() > 0 && Character.isLetterOrDigit(key.charAt(key.length() - 1))) {
            sb.append("\\b");
        }
        return sb.toString();
    }

    public static boolean isHybrid(String text) {
        if (text == null) return false;
        String clean = HYBRID_PLACEHOLDER.matcher(text).replaceAll("");
        Matcher matcher = ENGLISH_WORD.matcher(clean);
        Set<String> allowed = dynamicAllowedWords;
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(java.util.Locale.ROOT);
            if (!DEFAULT_ALLOWED_ENGLISH_WORDS.contains(word) && !allowed.contains(word)) {
                return true;
            }
        }
        return false;
    }


    // Pre-compiled phrase patterns cache for translateByPhrases() — avoids re-sorting
    // the entire dictionary and re-compiling regex patterns on every call
    private static class PhraseEntry {
        final String key;
        final String translation;
        final java.util.regex.Pattern pattern;

        PhraseEntry(String key, String translation, java.util.regex.Pattern pattern) {
            this.key = key;
            this.translation = translation;
            this.pattern = pattern;
        }
    }
    private static volatile java.util.List<PhraseEntry> cachedPhraseEntries = null;

    /**
     * Rebuild the phrase translation cache. Must be called whenever the dictionary changes.
     */
    static void rebuildPhraseCache() {
        Map<String, String> phrases = new java.util.HashMap<>(DICTIONARY);
        phrases.putAll(USER_DICTIONARY);
        java.util.List<String> sortedKeys = new java.util.ArrayList<>(phrases.keySet());
        sortedKeys.sort(java.util.Comparator.comparingInt(String::length).reversed()
            .thenComparing(java.util.Comparator.naturalOrder()));

        java.util.List<PhraseEntry> entries = new java.util.ArrayList<>();
        for (String key : sortedKeys) {
            if (key.trim().isEmpty() || key.startsWith("r:") || key.startsWith("gui.") || key.startsWith("item_type.") || key.startsWith("item_gender.") || key.contains(".") || key.length() < 3) {
                continue;
            }
            String translation = phrases.get(key);
            if (translation == null || translation.trim().isEmpty() || translation.equalsIgnoreCase(key)) {
                continue;
            }
            String regex = getWordBoundaryRegex(key);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
            entries.add(new PhraseEntry(key, translation, pattern));
        }
        cachedPhraseEntries = entries;
    }

    public static String translateByPhrases(String text) {
        return translateByPhrases(text, null);
    }

    private static String translateByPhrases(String text, java.util.Set<Source> sources) {
        if (text == null || text.trim().isEmpty()) return text;
        if (text.length() > MAX_REGEX_INPUT) return null;
        
        java.util.List<PhraseEntry> entries = cachedPhraseEntries;
        if (entries == null) {
            rebuildPhraseCache();
            entries = cachedPhraseEntries;
        }
        if (entries == null || entries.isEmpty()) return null;

        String result = text;
        String lowerResult = result.toLowerCase(java.util.Locale.ROOT);
        boolean changed = false;
        for (PhraseEntry entry : entries) {
            // Quick contains() check before running regex (much cheaper)
            if (!lowerResult.contains(entry.key)) {
                continue;
            }
            java.util.regex.Matcher matcher = entry.pattern.matcher(result);
            if (matcher.find()) {
                result = replaceBounded(matcher, Matcher.quoteReplacement(entry.translation));
                if (result == null) return null;
                lowerResult = result.toLowerCase(java.util.Locale.ROOT);
                if (sources != null) sources.add(USER_DICTIONARY.containsKey(entry.key) ? Source.USER : Source.GLOBAL);
                changed = true;
            }
        }
        if (changed) {
            if ("ru".equalsIgnoreCase(activeLanguage) && isHybrid(result)) {
                return null;
            }
            return result;
        }
        return null;
    }

    // Pre-compiled regex patterns cache for lookupRegex() — avoids compiling on every call
    private static class RegexEntry {
        final java.util.regex.Pattern pattern;
        final String replacement;
        volatile boolean invalidReplacement;

        RegexEntry(java.util.regex.Pattern pattern, String replacement) {
            this.pattern = pattern;
            this.replacement = replacement;
        }
    }
    private static volatile java.util.List<RegexEntry> cachedUserRegex = java.util.Collections.emptyList();
    private static volatile java.util.List<RegexEntry> cachedDictRegex = java.util.Collections.emptyList();

    /**
     * Rebuild the regex pattern caches. Must be called whenever dictionaries change.
     */
    static void rebuildRegexCache() {
        ru.fridorin.translator.TranslatorModClient.clearComponentCache();
        rebuildPhraseCache();
        cachedUserRegex = buildRegexEntries(USER_DICTIONARY);
        cachedDictRegex = buildRegexEntries(DICTIONARY);
    }

    private static java.util.List<RegexEntry> buildRegexEntries(Map<String, String> dict) {
        java.util.List<RegexEntry> entries = new java.util.ArrayList<>();
        java.util.List<Map.Entry<String, String>> sorted = new java.util.ArrayList<>(dict.entrySet());
        sorted.sort(java.util.Comparator.<Map.Entry<String, String>>comparingInt(e -> e.getKey().length()).reversed()
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, String> entry : sorted) {
            if (entries.size() >= MAX_REGEX_ENTRIES) break;
            String key = entry.getKey();
            if (key.startsWith("r:") && key.length() > 2 && key.length() <= MAX_KEY_LENGTH) {
                try {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                        key.substring(2), java.util.regex.Pattern.CASE_INSENSITIVE);
                    entries.add(new RegexEntry(pattern, entry.getValue()));
                } catch (PatternSyntaxException | StackOverflowError error) {
                    LOGGER.warn("Ignoring an invalid dictionary pattern");
                }
            }
        }
        return entries;
    }

    public static String lookupRegex(String text) {
        return lookupRegex(text, null);
    }

    private static String lookupRegex(String text, java.util.Set<Source> sources) {
        if (text == null || text.isEmpty() || text.length() > MAX_REGEX_INPUT) return null;
        RegexInput input = new RegexInput(text, 0, text.length(), new int[] { MAX_REGEX_CHAR_READS });
        try {
            String custom = applyRegexEntries(input, cachedUserRegex);
            if (custom != null) return recordSource(custom, Source.USER, sources);
            return recordSource(applyRegexEntries(input, cachedDictRegex), Source.GLOBAL, sources);
        } catch (RegexBudgetExceeded | StackOverflowError exhausted) {
            if (sources != null) sources.add(Source.REGEX_LIMIT);
            return null;
        }
    }

    private static String applyRegexEntries(RegexInput text, java.util.List<RegexEntry> entries) {
        for (RegexEntry entry : entries) {
            if (entry.invalidReplacement) continue;
            Matcher matcher = entry.pattern.matcher(text);
            try {
                if (matcher.find()) return replaceBounded(matcher, entry.replacement);
            } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
                entry.invalidReplacement = true;
                LOGGER.warn("Ignoring an invalid dictionary replacement");
            }
        }
        return null;
    }

    private static String replaceBounded(Matcher matcher, String replacement) {
        StringBuilder output = new StringBuilder();
        do {
            matcher.appendReplacement(output, replacement);
            if (output.length() > MAX_TRANSLATED_LENGTH) return null;
        } while (matcher.find());
        matcher.appendTail(output);
        return output.length() <= MAX_TRANSLATED_LENGTH ? output.toString() : null;
    }

    private record RegexInput(String text, int start, int end, int[] remaining) implements CharSequence {
        @Override public int length() { return end - start; }

        @Override public char charAt(int index) {
            Objects.checkIndex(index, length());
            if (--remaining[0] < 0) throw new RegexBudgetExceeded();
            return text.charAt(start + index);
        }

        @Override public CharSequence subSequence(int from, int to) {
            Objects.checkFromToIndex(from, to, length());
            return new RegexInput(text, start + from, start + to, remaining);
        }

        @Override public String toString() { return text.substring(start, end); }
    }

    private static final class RegexBudgetExceeded extends RuntimeException {
        RegexBudgetExceeded() { super(null, null, false, false); }
    }

    public static synchronized String lookup(String text) {
        return lookup(text, null);
    }

    private static String lookup(String text, java.util.Set<Source> sources) {
        if (text == null || text.length() > MAX_REGEX_INPUT) return null;
        TranslatorConfig config = TranslatorConfigManager.getConfig();
        if (!activeLanguage.equals(config.targetLanguage)) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;

        String cleanText = cleanPlaceholders(trimmed);
        if (cleanText.isEmpty()) return null;

        String match = getLiteral(cleanText);
        if (match != null) {
            String result = restoreLookupFormat(trimmed, cleanText, match);
            if (sources != null && result != null) {
                boolean userEntry = USER_DICTIONARY.containsKey(cleanText.toLowerCase(java.util.Locale.ROOT));
                sources.add(userEntry ? Source.USER : Source.GLOBAL);
            }
            return result;
        }

        if (config.regexTranslation) {
            String regexMatch = lookupRegex(cleanText, sources);
            if (regexMatch != null) {
                return restoreLookupFormat(trimmed, cleanText, regexMatch);
            }
        }

        String formattedRarity = tryTranslateRarityAndType(trimmed, cleanText);
        if (formattedRarity != null) {
            return recordSource(formattedRarity, Source.GLOBAL, sources);
        }

        String phraseTranslated = translateByPhrases(cleanText, sources);
        if (phraseTranslated != null) {
            return restoreLookupFormat(trimmed, cleanText, phraseTranslated);
        }

        return null;
    }

    private static String recordSource(String value, Source source, java.util.Set<Source> sources) {
        if (value != null && sources != null) sources.add(source);
        return value;
    }

    public enum Source { USER, GLOBAL, REGEX_LIMIT }
    public enum InspectionStatus { MATCH, EMPTY, TOO_LONG, LOADING, REGEX_DISABLED, REGEX_LIMIT, NO_MATCH }

    public record Inspection(String translation, java.util.List<Source> sources,
                             InspectionStatus status, java.util.List<RuleIssue> ruleIssues) {}
    public record RuleIssue(String pattern, String reason, int groupCount, String namedGroups) {}

    public static synchronized int entryCount() {
        return DICTIONARY.size() + USER_DICTIONARY.size();
    }

    public static synchronized Inspection inspect(String phrase) {
        var config = TranslatorConfigManager.getConfig();
        var sources = java.util.EnumSet.noneOf(Source.class);
        String result = lookup(phrase, sources);
        if (result == null && phrase != null && phrase.length() <= MAX_REGEX_INPUT) {
            var template = TranslationService.extractTemplates(phrase);
            if (!template.values.isEmpty()) {
                String matched = lookup(template.templatedText, sources);
                if (matched != null) result = TranslationService.restoreTemplates(matched, template.values);
            }
        }
        boolean budgetExceeded = sources.remove(Source.REGEX_LIMIT);
        InspectionStatus status;
        if (result != null) status = InspectionStatus.MATCH;
        else if (phrase == null || phrase.isBlank()) status = InspectionStatus.EMPTY;
        else if (phrase.length() > MAX_REGEX_INPUT) status = InspectionStatus.TOO_LONG;
        else if (!activeLanguage.equals(config.targetLanguage)) status = InspectionStatus.LOADING;
        else if (!config.regexTranslation) status = InspectionStatus.REGEX_DISABLED;
        else if (budgetExceeded) status = InspectionStatus.REGEX_LIMIT;
        else status = InspectionStatus.NO_MATCH;
        return new Inspection(result, java.util.List.copyOf(sources), status, inspectUserRules());
    }

    private static java.util.List<RuleIssue> inspectUserRules() {
        java.util.List<RuleIssue> issues = new java.util.ArrayList<>();
        USER_DICTIONARY.entrySet().stream().filter(entry -> entry.getKey().startsWith("r:"))
                .sorted(Map.Entry.comparingByKey()).limit(MAX_REGEX_ENTRIES).forEach(entry -> {
                    String rule = entry.getKey();
                    try {
                        Pattern pattern = Pattern.compile(rule.substring(2));
                        int groups = pattern.matcher("").groupCount();
                        var names = pattern.namedGroups().keySet();
                        if (!validReplacement(entry.getValue(), groups, names)) {
                            issues.add(new RuleIssue(rule, "replacement", groups, String.join(", ", new java.util.TreeSet<>(names))));
                        }
                    } catch (PatternSyntaxException | StackOverflowError error) {
                        issues.add(new RuleIssue(rule, "pattern", 0, ""));
                    }
                });
        return issues;
    }

    static boolean validReplacement(String replacement, int groups, java.util.Set<String> namedGroups) {
        for (int i = 0; i < replacement.length(); i++) {
            char ch = replacement.charAt(i);
            if (ch == '\\') {
                if (++i == replacement.length()) return false;
            } else if (ch == '$') {
                if (++i == replacement.length()) return false;
                char next = replacement.charAt(i);
                if (next == '{') {
                    int end = replacement.indexOf('}', i + 1);
                    if (end < 0 || !namedGroups.contains(replacement.substring(i + 1, end))) return false;
                    i = end;
                } else if (next < '0' || next > '9' || next - '0' > groups) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String tryTranslateRarityAndType(String original, String cleanText) {
        int start = 0;
        int end = cleanText.length();
        while (start < end && !isAsciiLetter(cleanText.charAt(start))) start++;
        while (end > start && !isAsciiLetter(cleanText.charAt(end - 1))) end--;
        String baseLine = cleanText.substring(start, end);
        String upperText = baseLine.toUpperCase(java.util.Locale.ROOT);

        String foundRarity = null;
        if (upperText.startsWith("VERY SPECIAL ")) foundRarity = "VERY SPECIAL";
        else if (upperText.startsWith("SPECIAL ")) foundRarity = "SPECIAL";
        else if (upperText.startsWith("COMMON ")) foundRarity = "COMMON";
        else if (upperText.startsWith("UNCOMMON ")) foundRarity = "UNCOMMON";
        else if (upperText.startsWith("RARE ")) foundRarity = "RARE";
        else if (upperText.startsWith("EPIC ")) foundRarity = "EPIC";
        else if (upperText.startsWith("LEGENDARY ")) foundRarity = "LEGENDARY";
        else if (upperText.startsWith("MYTHIC ")) foundRarity = "MYTHIC";
        else if (upperText.startsWith("DIVINE ")) foundRarity = "DIVINE";

        if (foundRarity != null) {
            String remainder = upperText.substring(foundRarity.length()).trim();
            boolean isDungeon = remainder.startsWith("DUNGEON ");
            if (isDungeon) {
                remainder = remainder.substring(8).trim();
            }
            String mappedType = ITEM_TYPES.get(remainder);
            String gender = ITEM_TYPES_GENDER.get(remainder);
            if (mappedType != null && gender != null) {
                String dungeonFormat = DICTIONARY.get("dungeon_format");
                if (dungeonFormat == null) {
                    dungeonFormat = "DUNGEON {type}";
                }
                String translatedType = isDungeon ? dungeonFormat.replace("{type}", mappedType) : mappedType;

                String format = DICTIONARY.get("rarity_format");
                if (format == null) {
                    format = "{rarity} {type}";
                }
                String translatedRarity = getRarityTranslation(foundRarity, gender);
                String translatedRarityText = format
                    .replace("{rarity}", translatedRarity)
                    .replace("{type}", translatedType);
                
                String cleanRarityLine = cleanText.substring(0, start) + translatedRarityText + cleanText.substring(end);
                return restoreLookupFormat(original, cleanText, cleanRarityLine);
            }
        }

        int colonIndex = cleanText.indexOf(":");
        if (colonIndex != -1) {
            String key = cleanText.substring(0, colonIndex).trim();
            String translatedKey = getLiteral(key);
            if (translatedKey != null) {
                int origColonIdx = original.indexOf(":");
                if (origColonIdx != -1) {
                    String keyPart = original.substring(0, origColonIdx).trim();
                    String valuePart = original.substring(origColonIdx + 1).trim();
                    
                    String restoredKey = restoreLookupFormat(keyPart, key, translatedKey);
                    if (restoredKey == null) return null;
                    String restoredValue = translateValuePart(valuePart);
                    return restoredKey + ": " + restoredValue;
                }
            }
        }

        if (cleanText.regionMatches(true, 0, "requires ", 0, 9)
                || cleanText.regionMatches(true, 0, "requires:", 0, 9)) {
            String cleanRequirement = cleanText.substring(9).trim();
            Matcher level = REQUIREMENT_LEVEL.matcher(cleanRequirement);
            if (level.find()) {
                String skillName = cleanRequirement.substring(0, level.start()).trim();
                String levelVal = cleanRequirement.substring(level.end()).trim();
                String translatedSkill = getLiteral(skillName);
                if (translatedSkill == null) translatedSkill = skillName;

                String skillFormat = DICTIONARY.get("requirement_skill_format");
                if (skillFormat == null) {
                    skillFormat = "Requires {skill} Level {level}";
                }
                String translatedReq = skillFormat
                    .replace("{skill}", translatedSkill)
                    .replace("{level}", levelVal);
                return restoreLookupFormat(original, cleanText, translatedReq);
            } else {
                int lastSpace = cleanRequirement.lastIndexOf(' ');
                if (lastSpace != -1) {
                    String slayerName = cleanRequirement.substring(0, lastSpace).trim();
                    String slayerLevel = cleanRequirement.substring(lastSpace + 1).trim();
                    String translatedSlayer = getLiteral(slayerName);
                    if (translatedSlayer == null) translatedSlayer = slayerName;

                    String slayerFormat = DICTIONARY.get("requirement_slayer_format");
                    if (slayerFormat == null) {
                        slayerFormat = "Requires {slayer} Level {level}";
                    }
                    String translatedReq = slayerFormat
                        .replace("{slayer}", translatedSlayer)
                        .replace("{level}", slayerLevel);
                    return restoreLookupFormat(original, cleanText, translatedReq);
                }
            }
        }

        return null;
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
    }

    private static String translateValuePart(String valueStr) {
        if (valueStr == null || valueStr.trim().isEmpty()) return valueStr;
        String trimmed = valueStr.trim();
        String cleanVal = cleanPlaceholders(trimmed);
        if (cleanVal.isEmpty()) return valueStr;
        
        String match = getLiteral(cleanVal);
        if (match != null) {
            return restoreValueFormat(trimmed, cleanVal, match);
        }
        
        
        if (TranslatorConfigManager.getConfig().regexTranslation) {
            String regexMatch = lookupRegex(cleanVal);
            if (regexMatch != null) {
                return restoreValueFormat(trimmed, cleanVal, regexMatch);
            }
        }
        
        
        String phraseTranslated = translateByPhrases(cleanVal);
        if (phraseTranslated != null) {
            return restoreValueFormat(trimmed, cleanVal, phraseTranslated);
        }
        
        
        String coinsTrans = DICTIONARY.get("coins");
        if (coinsTrans == null) coinsTrans = "Coins";
        String eachTrans = DICTIONARY.get("each");
        if (eachTrans == null) eachTrans = "each";
        String notDonatedTrans = DICTIONARY.get("not donated");
        if (notDonatedTrans == null) notDonatedTrans = "Not Donated";
        String donatedTrans = DICTIONARY.get("donated");
        if (donatedTrans == null) donatedTrans = "Donated";
        
        return trimmed
            .replace("Coins", coinsTrans)
            .replace("each", eachTrans)
            .replace("Not Donated", notDonatedTrans)
            .replace("Donated", donatedTrans);
    }

    private static String getLiteral(String text) {
        String key = text.toLowerCase(java.util.Locale.ROOT);
        String custom = USER_DICTIONARY.get(key);
        return custom == null ? DICTIONARY.get(key) : custom;
    }

    private static String restoreValueFormat(String original, String clean, String translation) {
        String restored = restoreLookupFormat(original, clean, translation);
        return restored == null ? original : restored;
    }

    public static void loadForLanguage(String language) {
        String lang = TranslatorConfigManager.normalizeLanguage(language);
        synchronized (FILE_LOCK) {
            Map<String, String> entries = readLanguageEntries(lang);
            synchronized (DOWNLOAD_LOCK) {
                dictionaryGeneration++;
                if (pendingDownload != null) pendingDownload.cancel(true);
                if (pendingResponse != null) pendingResponse.cancel(true);
                pendingDownload = null;
                pendingResponse = null;
                replaceLanguageEntries(lang, entries);
            }
        }
        if (!TranslatorConfigManager.getConfig().localOnly) downloadDictionaryAsync(lang);
    }

    private static Map<String, String> readLanguageEntries(String lang) {
        Map<String, String> entries = new LinkedHashMap<>();
        String resourcePath = "/assets/skyblock_translator/dictionary/dictionary_" + lang + ".json";
        try (var stream = SkyblockDictionary.class.getResourceAsStream(resourcePath)) {
            if (stream != null) entries.putAll(parseEntries(AtomicFiles.readUtf8(stream, MAX_DICTIONARY_BYTES)));
        } catch (IOException | IllegalStateException error) {
            LOGGER.warn("Could not read the bundled translation dictionary");
        }

        Path file = dictionaryPath("dictionary_" + lang + ".json");
        try {
            if (Files.exists(file)) {
                entries.putAll(readDictionary(file));
            } else if (!entries.isEmpty()) {
                saveDictionary(file, entries);
            }
        } catch (IOException | IllegalStateException error) {
            LOGGER.warn("Could not load the local translation dictionary");
        }

        return entries;
    }

    private static synchronized void replaceLanguageEntries(String lang, Map<String, String> entries) {
        activeLanguage = lang;
        DICTIONARY.clear();
        DICTIONARY.putAll(entries);
        rebuildDerivedData();
    }

    private static void rebuildDerivedData() {
        ITEM_TYPES.clear();
        ITEM_TYPES_GENDER.clear();
        for (Map.Entry<String, String> entry : DICTIONARY.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            
            if (key.startsWith("item_type.")) {
                String typeName = key.substring(10).trim().toUpperCase(java.util.Locale.ROOT);
                ITEM_TYPES.put(typeName, value);
            } else if (key.startsWith("item_gender.")) {
                String typeName = key.substring(12).trim().toUpperCase(java.util.Locale.ROOT);
                ITEM_TYPES_GENDER.put(typeName, value.toUpperCase(java.util.Locale.ROOT));
            }
        }

        updateDynamicLists();
        rebuildRegexCache();
    }

    static String normalizeDictionaryKey(String key) {
        String trimmed = key.trim();
        return trimmed.startsWith("r:") ? trimmed : trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    static Map<String, String> parseEntries(String json) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Expected a dictionary object");
            reader.beginObject();
            while (reader.hasNext()) {
                String key = normalizeDictionaryKey(reader.nextName());
                if (reader.peek() != JsonToken.STRING) throw new IOException("Expected a dictionary string");
                String value = reader.nextString().trim();
                if (key.isEmpty() || key.length() > MAX_KEY_LENGTH || value.length() > MAX_VALUE_LENGTH
                        || entries.size() >= MAX_ENTRIES || entries.putIfAbsent(key, value) != null) {
                    throw new IOException("Invalid, oversized or duplicate dictionary entry");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Unexpected dictionary content");
        }
        return entries;
    }

    private static Path dictionaryPath(String fileName) {
        return FabricLoader.getInstance().getConfigDir().resolve("skyblock_translator").resolve(fileName);
    }

    private static Map<String, String> readDictionary(Path file) throws IOException {
        return parseEntries(AtomicFiles.read(file, MAX_DICTIONARY_BYTES));
    }

    private static void saveDictionary(Path file, Map<String, String> entries) throws IOException {
        if (entries.size() > MAX_ENTRIES) throw new IOException("Too many dictionary entries");
        AtomicFiles.write(file, GSON.toJson(new TreeMap<>(entries)), MAX_DICTIONARY_BYTES);
    }

    public static CompletableFuture<Integer> downloadDictionaryAsync(String lang) {
        synchronized (DOWNLOAD_LOCK) {
            if (TranslatorConfigManager.getConfig().localOnly || lang == null || !DOWNLOAD_LANGUAGES.contains(lang) || !lang.equals(activeLanguage)) {
                return CompletableFuture.completedFuture(-1);
            }
            if (pendingDownload != null && !pendingDownload.isDone()) return pendingDownload.copy();
            pendingDownload = downloadDictionary(lang, dictionaryGeneration);
            return pendingDownload.copy();
        }
    }

    private static CompletableFuture<Integer> downloadDictionary(String lang, long generation) {
        String repository = "https://raw.githubusercontent.com/" + TranslatorConfig.GITHUB_USERNAME + "/"
            + TranslatorConfig.GITHUB_REPOSITORY + "/" + TranslatorConfig.GITHUB_BRANCH + "/";
        String dictionary = TranslatorConfig.GITHUB_DICT_PATH.replace("{lang}", lang);
        return fetchDictionary(repository + dictionary, generation)
            .thenCompose(response -> response.statusCode() == 404 && "ru".equals(lang)
                ? fetchDictionary(repository + "dictionary.json", generation) : CompletableFuture.completedFuture(response))
            .thenApplyAsync(response -> {
                if (response.statusCode() != 200) return -1;
                try {
                    Map<String, String> downloaded = parseEntries(response.body());
                    if (downloaded.isEmpty()) return -1;
                    return installDownload(lang, generation, downloaded);
                } catch (IOException | IllegalStateException error) {
                    LOGGER.warn("Could not apply the downloaded translation dictionary");
                    return -1;
                }
            }).exceptionally(error -> {
                LOGGER.debug("Could not download the translation dictionary");
                return -1;
            });
    }

    private static CompletableFuture<HttpResponse<String>> fetchDictionary(String url, long generation) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5)).GET().build();
        synchronized (DOWNLOAD_LOCK) {
            if (generation != dictionaryGeneration) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
            var response = HTTP_CLIENT.sendAsync(request, BoundedHttpBody.ofUtf8String(MAX_DICTIONARY_BYTES));
            pendingResponse = response;
            return response.copy().orTimeout(5, TimeUnit.SECONDS).whenComplete((result, error) -> {
                if (error != null) response.cancel(true);
            });
        }
    }

    private static int installDownload(String lang, long generation, Map<String, String> downloaded)
            throws IOException {
        synchronized (FILE_LOCK) {
            if (generation != dictionaryGeneration || !lang.equals(activeLanguage) || TranslatorConfigManager.getConfig().localOnly) return -1;
            Map<String, String> updated = new LinkedHashMap<>(DICTIONARY);
            updated.putAll(downloaded);
            if (!updated.equals(DICTIONARY)) {
                saveDictionary(dictionaryPath("dictionary_" + lang + ".json"), updated);
                replaceLanguageEntries(lang, updated);
            }
            return downloaded.size();
        }
    }

    public static void loadUserDictionary() {
        Path file = dictionaryPath("user_dictionary.json");
        synchronized (FILE_LOCK) {
            try {
                if (!Files.exists(file)) {
                    saveDictionary(file, Map.of("info", "Add translations as English text: translated text"));
                }
                replaceUserEntries(readDictionary(file));
            } catch (IOException | IllegalStateException error) {
                LOGGER.warn("Could not load the user dictionary");
            }
        }
    }

    public static boolean addUserTranslation(String english, String translation) {
        return addUserTranslation(dictionaryPath("user_dictionary.json"), english, translation);
    }

    static boolean addUserTranslation(Path file, String english, String translation) {
        if (english == null || translation == null) return false;
        String key = normalizeDictionaryKey(english);
        String value = translation.trim();
        if (key.isEmpty() || key.equals("info") || key.length() > MAX_KEY_LENGTH
                || value.isEmpty() || value.length() > MAX_VALUE_LENGTH) return false;
        if (key.startsWith("r:")) {
            try {
                if (key.length() <= 2) return false;
                Pattern.compile(key.substring(2), Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException | StackOverflowError invalid) {
                return false;
            }
        }

        synchronized (FILE_LOCK) {
            try {
                Map<String, String> entries = Files.exists(file) ? readDictionary(file) : new LinkedHashMap<>();
                entries.putIfAbsent("info", "Add translations as English text: translated text");
                entries.put(key, value);
                saveDictionary(file, entries);
                replaceUserEntries(entries);
                return true;
            } catch (IOException | IllegalStateException error) {
                LOGGER.warn("Could not save the user dictionary");
                return false;
            }
        }
    }

    private static synchronized void replaceUserEntries(Map<String, String> entries) {
        USER_DICTIONARY.clear();
        entries.forEach((key, value) -> {
            if (!key.equals("info")) USER_DICTIONARY.put(key, value);
        });
        updateDynamicLists();
        rebuildRegexCache();
    }

    public static String getTranslationOrUserTranslation(String key) {
        if (key == null) return null;
        String lower = normalizeDictionaryKey(key);
        String userMatch = USER_DICTIONARY.get(lower);
        if (userMatch != null) return userMatch + " §e(User Dictionary)§r";
        String dictMatch = DICTIONARY.get(lower);
        if (dictMatch != null) return dictMatch + " §7(GitHub/Default)§r";
        return null;
    }
}
