package ru.fridorin.translator.config;

public class TranslatorConfig {
    public boolean enabled = true;
    public boolean translateChat = true;
    public boolean translateGuildChat = true;
    public boolean translatePartyChat = true;
    public boolean translateCoopChat = true;
    public boolean translateDirectMessages = false;
    public boolean translatePublicChat = true;
    public boolean translateSystemMessages = true;
    public boolean translateTooltips = true;
    public boolean translateInventoryTitles = true;
    public boolean translateScoreboard = false;
    public boolean translateNpcDialogues = true;
    public boolean translateNpcNames = true;
    public boolean translateMobNames = true;
    public boolean translateHolograms = true;
    public boolean translateEnchantments = true;
    public boolean translateEnchantmentDescriptions = true;
    public boolean translateAbilityNames = true;
    public boolean translateAbilityDescriptions = true;
    public boolean translateRarity = true;
    public boolean translateItemNames = true;
    public boolean translateOutgoingChat = false;
    public boolean showHoverOriginal = true;
    public boolean translateTabList = true;
    public boolean regexTranslation = true;
    public String chatDisplayMode = "SEPARATE_LINE";
    public volatile String targetLanguage = "ru";
    public volatile String apiProvider = "GOOGLE_FREE";
    public volatile String deeplApiKey = "";
    public volatile String yandexApiKey = "";
    @com.google.gson.annotations.SerializedName("apiKey")
    String legacyApiKey;
    String unassignedLegacyApiKey;
    public int configVersion = 3;

    public volatile boolean localOnly;
    public volatile boolean onlineChat = true;
    public volatile boolean onlineNpc = true;
    public volatile boolean onlineTooltips = true;
    public volatile boolean onlineWorld = true;
    public volatile boolean allowGoogleFallback;
    public volatile boolean persistChatCache;

    public String keyFor(String provider) {
        if (provider == null) return "";
        return switch (provider) {
            case "DEEPL_FREE" -> deeplApiKey;
            case "YANDEX" -> yandexApiKey;
            default -> "";
        };
    }

    public boolean ignoreAbilityCooldowns = true;
    public boolean ignoreDamageIndicators = true;
    public boolean ignoreModPrefixes = true;
    public boolean ignoreDungeonStats = true;
    public boolean ignoreSlayerAlerts = true;
    public boolean ignoreLobbyJoinLeft = true;
    public boolean ignoreClickActions = true;
    public boolean ignoreBazaarAndBin = true;
    public boolean ignoreUuidsAndIds = true;
    public boolean ignoreUrlsAndIps = true;
    public boolean ignoreDatesAndLobbies = true;
    public boolean disableOnShift = true;
    public boolean onlyOnHypixel = true;

    
    public static final String GITHUB_USERNAME = "fridorin";
    public static final String GITHUB_REPOSITORY = "Skyblock-Translator-Mod";
    public static final String GITHUB_BRANCH = "main";
    public static final String GITHUB_DICT_PATH = "dictionaries/dictionary_{lang}.json";

}

