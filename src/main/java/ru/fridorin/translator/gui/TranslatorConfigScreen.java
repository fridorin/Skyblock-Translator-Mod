package ru.fridorin.translator.gui;

import ru.fridorin.translator.config.TranslatorConfig;
import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.cache.TranslationCache;
import ru.fridorin.translator.TranslatorModClient;
import ru.fridorin.translator.service.SkyblockDictionary;
import ru.fridorin.translator.service.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.gui.controllers.string.IStringController;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.KeyMapping;

import java.util.List;
import java.net.URI;

public class TranslatorConfigScreen {
    private static final String MASKED_API_KEY = "••••••••";
    private static final URI ISSUES_PAGE = URI.create("https://github.com/fridorin/Skyblock-Translator-Mod/issues");

    private static final java.util.Map<String, String> LANGUAGE_MAP = new java.util.LinkedHashMap<>();
    static {
        LANGUAGE_MAP.put("en", "English (off)");
        LANGUAGE_MAP.put("ru", "Русский (Russian)");
        LANGUAGE_MAP.put("es", "Español (Spanish)");
        LANGUAGE_MAP.put("fr", "Français (French)");
        LANGUAGE_MAP.put("de", "Deutsch (German)");
        LANGUAGE_MAP.put("zh", "中文 (Chinese)");
        LANGUAGE_MAP.put("ja", "日本語 (Japanese)");
        LANGUAGE_MAP.put("ko", "한국어 (Korean)");
        LANGUAGE_MAP.put("pt", "Português (Portuguese)");
        LANGUAGE_MAP.put("it", "Italiano (Italian)");
        LANGUAGE_MAP.put("tr", "Türkçe (Turkish)");
        LANGUAGE_MAP.put("pl", "Polski (Polish)");
        LANGUAGE_MAP.put("uk", "Українська (Ukrainian)");
        LANGUAGE_MAP.put("be", "Беларуская (Belarusian)");
        LANGUAGE_MAP.put("kk", "Қазақша (Kazakh)");
        LANGUAGE_MAP.put("ar", "العربية (Arabic)");
        LANGUAGE_MAP.put("hi", "हिन्दी (Hindi)");
        LANGUAGE_MAP.put("vi", "Tiếng Việt (Vietnamese)");
        LANGUAGE_MAP.put("th", "ไทย (Thai)");
        LANGUAGE_MAP.put("nl", "Nederlands (Dutch)");
        LANGUAGE_MAP.put("sv", "Svenska (Swedish)");
        LANGUAGE_MAP.put("no", "Norsk (Norwegian)");
        LANGUAGE_MAP.put("da", "Dansk (Danish)");
        LANGUAGE_MAP.put("fi", "Suomi (Finnish)");
        LANGUAGE_MAP.put("cs", "Čeština (Czech)");
        LANGUAGE_MAP.put("el", "Ελληνικά (Greek)");
        LANGUAGE_MAP.put("bg", "Български (Bulgarian)");
        LANGUAGE_MAP.put("ro", "Română (Romanian)");
        LANGUAGE_MAP.put("hu", "Magyar (Hungarian)");
        LANGUAGE_MAP.put("sk", "Slovenčina (Slovak)");
        LANGUAGE_MAP.put("id", "Bahasa Indonesia (Indonesian)");
    }

    private static Component translate(String key) {
        return Component.translatable("gui.skyblock_translator." + key);
    }

    private static Component translate(String key, Object... args) {
        return Component.translatable("gui.skyblock_translator." + key, args);
    }

    static Option<String> languageOption(TranslatorConfig config) {
        var choices = new java.util.LinkedHashMap<String, String>();
        LANGUAGE_MAP.forEach((code, name) -> choices.put(name, code));
        choices.putIfAbsent(config.targetLanguage, config.targetLanguage);
        return Option.<String>createBuilder()
                .name(translate("opt.target_language"))
                .binding(LANGUAGE_MAP.get("ru"), () -> LANGUAGE_MAP.getOrDefault(config.targetLanguage, config.targetLanguage),
                        label -> config.targetLanguage = choices.get(label))
                .controller(option -> DropdownStringControllerBuilder.create(option)
                        .values(List.copyOf(choices.keySet())).allowAnyValue(false))
                .build();
    }

    private static Option<Boolean> toggle(String key, boolean defaultValue, BooleanSupplier get, Consumer<Boolean> set) {
        return Option.<Boolean>createBuilder().name(translate(key))
                .binding(defaultValue, get::getAsBoolean, set)
                .controller(TickBoxControllerBuilder::create).build();
    }

    private static Option<String> providerOption(TranslatorConfig config) {
        return Option.<String>createBuilder().name(translate("opt.provider"))
                .description(OptionDescription.of(translate("opt.provider.desc")))
                .binding("GOOGLE_FREE", () -> config.apiProvider, value -> config.apiProvider = value)
                .controller(option -> CyclingListControllerBuilder.create(option)
                        .values(List.of("GOOGLE_FREE", "DEEPL_FREE", "YANDEX"))
                        .formatValue(value -> switch (value) {
                            case "GOOGLE_FREE" -> translate("provider.google");
                            case "DEEPL_FREE" -> translate("provider.deepl");
                            case "YANDEX" -> translate("provider.yandex");
                            default -> Component.literal(value);
                        })).build();
    }

    private static Option<ApiKeyField> keyOption(String label, Supplier<String> get, Consumer<String> set) {
        boolean[] revealed = {false};
        return Option.<ApiKeyField>createBuilder().name(translate(label))
                .description(OptionDescription.of(translate("opt.key.desc")))
                .binding(new ApiKeyField("", false), () -> new ApiKeyField(get.get(), revealed[0]), field -> {
                    set.accept(field.key());
                    revealed[0] = field.revealed();
                })
                .customController(ApiKeyController::new).build();
    }

    private static ButtonOption revealKey(String label, Option<ApiKeyField> key) {
        return ButtonOption.createBuilder().name(translate(label))
                .description(OptionDescription.of(translate("opt.showkey.desc")))
                .action((screen, button) -> {
                    ApiKeyField field = key.pendingValue();
                    key.requestSet(new ApiKeyField(field.key(), !field.revealed()));
                }).build();
    }

    private static ButtonOption testProvider(Option<String> provider, Option<ApiKeyField> deepl,
                                             Option<ApiKeyField> yandex, Option<Boolean> localOnly) {
        return ButtonOption.createBuilder().name(translate("provider.test")).action((screen, button) -> {
            String selected = provider.pendingValue();
            String key = switch (selected) {
                case "DEEPL_FREE" -> deepl.pendingValue().key();
                case "YANDEX" -> yandex.pendingValue().key();
                default -> "";
            };
            Minecraft client = Minecraft.getInstance();
            var title = translate("provider.test");
            Runnable close = () -> client.setScreen(screen);
            var waitScreen = new AlertScreen(close, title, translate("provider.testing"));
            client.setScreen(waitScreen);
            TranslationService.testConnectionAsync(selected, key, localOnly.pendingValue())
                    .thenAccept(result -> client.schedule(() -> {
                        if (TranslatorModClient.currentScreen(client) == waitScreen) {
                            var category = TranslationService.failureLabel(result);
                            client.setScreen(new AlertScreen(close, title, translate("provider.result", selected, category)));
                        }
                    }));
        }).build();
    }

    static void inspectDictionary(Screen parent, String phrase) {
        var inspection = SkyblockDictionary.inspect(phrase);
        var status = Component.translatable("gui.skyblock_translator.dictionary.result."
                + inspection.status().name().toLowerCase(java.util.Locale.ROOT));
        var result = Component.empty().append(status);
        if (inspection.translation() != null) {
            result.append("\n").append(Component.literal(inspection.translation()));
            for (var source : inspection.sources()) {
                result.append("\n").append(Component.translatable("gui.skyblock_translator.dictionary.source."
                        + source.name().toLowerCase(java.util.Locale.ROOT)));
            }
        }
        for (var issue : inspection.ruleIssues().stream().limit(3).toList()) {
            result.append("\n").append("⦁ ").append(Component.literal(issue.pattern())).append("\n");
            if ("replacement".equals(issue.reason())) {
                result.append(translate("dictionary.invalid_replacement", issue.groupCount(), issue.namedGroups()));
            } else {
                result.append(translate("dictionary.invalid_pattern"));
            }
        }
        if (inspection.ruleIssues().size() > 3) {
            result.append("\n").append(translate("dictionary.more_issues", inspection.ruleIssues().size() - 3));
        }
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new AlertScreen(() -> client.setScreen(parent), translate("dictionary.test"), result));
    }

    public static Screen createScreen(Screen parent) {
        TranslatorConfig config = TranslatorConfigManager.getConfig();

        
        KeyMapping keySettingsMapping = TranslatorModClient.getKeyOpenSettings();
        KeyMapping keyToggleMapping = TranslatorModClient.getKeyToggleTranslator();

        String currentSettingsKey = keySettingsMapping != null ? keySettingsMapping.getTranslatedKeyMessage().getString() : "NONE";
        String currentToggleKey = keyToggleMapping != null ? keyToggleMapping.getTranslatedKeyMessage().getString() : "NONE";
        var deepl = keyOption("opt.deepl_key", () -> config.deeplApiKey, value -> config.deeplApiKey = value);
        var yandex = keyOption("opt.yandex_key", () -> config.yandexApiKey, value -> config.yandexApiKey = value);
        var provider = providerOption(config);
        var localOnly = toggle("opt.local_only", false, () -> config.localOnly, value -> config.localOnly = value);
        String savedLanguage = config.targetLanguage;
        boolean savedLocalMode = config.localOnly;
        YetAnotherConfigLib configLib = YetAnotherConfigLib.createBuilder()
            .title(translate("title"))
            .save(() -> {
                TranslatorConfigManager.save();
                if (!savedLanguage.equals(config.targetLanguage) || savedLocalMode != config.localOnly) {
                    TranslatorModClient.reloadTranslations();
                }
            })
            
            
            .category(ConfigCategory.createBuilder()
                .name(translate("cat.language"))
                .tooltip(translate("cat.language.tooltip"))
                .group(OptionGroup.createBuilder()
                    .name(translate("group.language_select"))
                    .option(languageOption(config))
                    .build())
                .build())
            
            
            .category(ConfigCategory.createBuilder()
                .name(translate("cat.main"))
                .tooltip(translate("cat.main.tooltip"))
                
                .group(OptionGroup.createBuilder()
                    .name(translate("group.global"))
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.enabled"))
                        .description(OptionDescription.of(translate("opt.enabled.desc")))
                        .binding(true, () -> config.enabled, val -> config.enabled = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.only_hypixel"))
                        .description(OptionDescription.of(translate("opt.only_hypixel.desc")))
                        .binding(true, () -> config.onlyOnHypixel, val -> config.onlyOnHypixel = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.disable_on_shift"))
                        .description(OptionDescription.of(translate("opt.disable_on_shift.desc")))
                        .binding(true, () -> config.disableOnShift, val -> config.disableOnShift = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.regex_translation"))
                        .description(OptionDescription.of(translate("opt.regex_translation.desc")))
                        .binding(true, () -> config.regexTranslation, val -> config.regexTranslation = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .build())
                
                .group(OptionGroup.createBuilder()
                    .name(translate("group.keybinds"))
                    .option(ButtonOption.createBuilder()
                        .name(translate("opt.key_settings"))
                        .description(OptionDescription.of(translate("opt.key_settings.desc")))
                        .action((opt, btn) -> {
                            net.minecraft.client.Minecraft.getInstance().setScreen(
                                new KeyBindCaptureScreen(opt, keySettingsMapping, () -> opt.pendingChanges() ? opt : createScreen(parent))
                            );
                        })
                        .text(Component.literal(currentSettingsKey))
                        .build())
                    .option(ButtonOption.createBuilder()
                        .name(translate("opt.key_toggle"))
                        .description(OptionDescription.of(translate("opt.key_toggle.desc")))
                        .action((opt, btn) -> {
                            net.minecraft.client.Minecraft.getInstance().setScreen(
                                new KeyBindCaptureScreen(opt, keyToggleMapping, () -> opt.pendingChanges() ? opt : createScreen(parent))
                            );
                        })
                        .text(Component.literal(currentToggleKey))
                        .build())
                    .build())
                
                .group(OptionGroup.createBuilder()
                    .name(translate("group.api"))
                    .option(localOnly)
                    .option(toggle("opt.online_chat", true, () -> config.onlineChat, value -> config.onlineChat = value))
                    .option(toggle("opt.online_npc", true, () -> config.onlineNpc, value -> config.onlineNpc = value))
                    .option(toggle("opt.online_tooltips", true, () -> config.onlineTooltips, value -> config.onlineTooltips = value))
                    .option(toggle("opt.online_world", true, () -> config.onlineWorld, value -> config.onlineWorld = value))
                    .option(toggle("opt.online_fallback", false, () -> config.allowGoogleFallback, value -> config.allowGoogleFallback = value))
                    .option(toggle("opt.persist_chat_cache", false, () -> config.persistChatCache, value -> config.persistChatCache = value))
                    .option(provider)
                    .option(revealKey("opt.reveal_deepl", deepl))
                    .option(deepl)
                    .option(revealKey("opt.reveal_yandex", yandex))
                    .option(yandex)
                    .option(testProvider(provider, deepl, yandex, localOnly))
                    .build())
                .build())
            
            
            .category(ConfigCategory.createBuilder()
                .name(translate("cat.chat"))
                .tooltip(translate("cat.chat.tooltip"))
                .group(OptionGroup.createBuilder()
                    .name(translate("group.chat"))
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.translate_chat"))
                        .description(OptionDescription.of(translate("opt.translate_chat.desc")))
                        .binding(true, () -> config.translateChat, val -> config.translateChat = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.translate_npc"))
                        .description(OptionDescription.of(translate("opt.translate_npc.desc")))
                        .binding(true, () -> config.translateNpcDialogues, val -> config.translateNpcDialogues = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<String>createBuilder()
                        .name(translate("opt.chat_display_mode"))
                        .description(OptionDescription.of(translate("opt.chat_display_mode.desc")))
                        .binding("SEPARATE_LINE", () -> config.chatDisplayMode, val -> config.chatDisplayMode = val)
                        .controller(opt -> CyclingListControllerBuilder.create(opt)
                            .values(List.of("SEPARATE_LINE", "APPEND", "INLINE", "REPLACE"))
                            .formatValue(val -> {
                                switch(val) {
                                    case "SEPARATE_LINE": return translate("mode.separate_line");
                                    case "APPEND": return translate("mode.append");
                                    case "INLINE": return translate("mode.inline");
                                    case "REPLACE": return translate("mode.replace");
                                    default: return Component.literal(val);
                                }
                            }))
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.hover_original"))
                        .description(OptionDescription.of(translate("opt.hover_original.desc")))
                        .binding(true, () -> config.showHoverOriginal, val -> config.showHoverOriginal = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.outgoing"))
                        .description(OptionDescription.of(translate("opt.outgoing.desc")))
                        .binding(false, () -> config.translateOutgoingChat, val -> config.translateOutgoingChat = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .build())
                .group(OptionGroup.createBuilder()
                    .name(translate("group.channels"))
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.guild_chat"))
                        .description(OptionDescription.of(translate("opt.guild_chat.desc")))
                        .binding(true, () -> config.translateGuildChat, val -> config.translateGuildChat = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.party_chat"))
                        .description(OptionDescription.of(translate("opt.party_chat.desc")))
                        .binding(true, () -> config.translatePartyChat, val -> config.translatePartyChat = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.coop_chat"))
                        .description(OptionDescription.of(translate("opt.coop_chat.desc")))
                        .binding(true, () -> config.translateCoopChat, val -> config.translateCoopChat = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.dm_chat"))
                        .description(OptionDescription.of(translate("opt.dm_chat.desc")))
                        .binding(false, () -> config.translateDirectMessages, val -> config.translateDirectMessages = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.public_chat"))
                        .description(OptionDescription.of(translate("opt.public_chat.desc")))
                        .binding(true, () -> config.translatePublicChat, val -> config.translatePublicChat = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.system_chat"))
                        .description(OptionDescription.of(translate("opt.system_chat.desc")))
                        .binding(true, () -> config.translateSystemMessages, val -> config.translateSystemMessages = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .build())
                .build())
            
            
            .category(ConfigCategory.createBuilder()
                .name(translate("cat.world"))
                .tooltip(translate("cat.world.tooltip"))
                .group(OptionGroup.createBuilder()
                    .name(translate("group.entities"))
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.holograms"))
                        .description(OptionDescription.of(translate("opt.holograms.desc")))
                        .binding(true, () -> config.translateHolograms, val -> config.translateHolograms = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.mobs"))
                        .description(OptionDescription.of(translate("opt.mobs.desc")))
                        .binding(true, () -> config.translateMobNames, val -> config.translateMobNames = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.npc_names"))
                        .description(OptionDescription.of(translate("opt.npc_names.desc")))
                        .binding(true, () -> config.translateNpcNames, val -> config.translateNpcNames = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.translate_tablist"))
                        .description(OptionDescription.of(translate("opt.translate_tablist.desc")))
                        .binding(true, () -> config.translateTabList, val -> config.translateTabList = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .build())
                .build())
            
            
            .category(ConfigCategory.createBuilder()
                .name(translate("cat.gui"))
                .tooltip(translate("cat.gui.tooltip"))
                .group(OptionGroup.createBuilder()
                    .name(translate("group.gui"))
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.tooltips"))
                        .description(OptionDescription.of(translate("opt.tooltips.desc")))
                        .binding(true, () -> config.translateTooltips, val -> config.translateTooltips = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.item_names"))
                        .description(OptionDescription.of(translate("opt.item_names.desc")))
                        .binding(false, () -> config.translateItemNames, val -> config.translateItemNames = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.enchantments"))
                        .description(OptionDescription.of(translate("opt.enchantments.desc")))
                        .binding(false, () -> config.translateEnchantments, val -> config.translateEnchantments = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.translate_enchantment_descriptions"))
                        .description(OptionDescription.of(translate("opt.translate_enchantment_descriptions.desc")))
                        .binding(true, () -> config.translateEnchantmentDescriptions, val -> config.translateEnchantmentDescriptions = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.translate_ability_names"))
                        .description(OptionDescription.of(translate("opt.translate_ability_names.desc")))
                        .binding(false, () -> config.translateAbilityNames, val -> config.translateAbilityNames = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.translate_ability_descriptions"))
                        .description(OptionDescription.of(translate("opt.translate_ability_descriptions.desc")))
                        .binding(true, () -> config.translateAbilityDescriptions, val -> config.translateAbilityDescriptions = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.translate_rarity"))
                        .description(OptionDescription.of(translate("opt.translate_rarity.desc")))
                        .binding(false, () -> config.translateRarity, val -> config.translateRarity = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.titles"))
                        .description(OptionDescription.of(translate("opt.titles.desc")))
                        .binding(true, () -> config.translateInventoryTitles, val -> config.translateInventoryTitles = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.scoreboard"))
                        .description(OptionDescription.of(translate("opt.scoreboard.desc")))
                        .binding(false, () -> config.translateScoreboard, val -> config.translateScoreboard = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .build())
                .build())

            
            .category(ConfigCategory.createBuilder()
                .name(translate("cat.filters"))
                .tooltip(translate("cat.filters.tooltip"))
                .group(OptionGroup.createBuilder()
                    .name(translate("group.technical_filters"))
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_cooldowns"))
                        .description(OptionDescription.of(translate("opt.ignore_cooldowns.desc")))
                        .binding(true, () -> config.ignoreAbilityCooldowns, val -> config.ignoreAbilityCooldowns = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_damage"))
                        .description(OptionDescription.of(translate("opt.ignore_damage.desc")))
                        .binding(true, () -> config.ignoreDamageIndicators, val -> config.ignoreDamageIndicators = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_prefixes"))
                        .description(OptionDescription.of(translate("opt.ignore_prefixes.desc")))
                        .binding(true, () -> config.ignoreModPrefixes, val -> config.ignoreModPrefixes = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_dungeons"))
                        .description(OptionDescription.of(translate("opt.ignore_dungeons.desc")))
                        .binding(true, () -> config.ignoreDungeonStats, val -> config.ignoreDungeonStats = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_slayer"))
                        .description(OptionDescription.of(translate("opt.ignore_slayer.desc")))
                        .binding(true, () -> config.ignoreSlayerAlerts, val -> config.ignoreSlayerAlerts = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_lobby"))
                        .description(OptionDescription.of(translate("opt.ignore_lobby.desc")))
                        .binding(true, () -> config.ignoreLobbyJoinLeft, val -> config.ignoreLobbyJoinLeft = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_clicks"))
                        .description(OptionDescription.of(translate("opt.ignore_clicks.desc")))
                        .binding(true, () -> config.ignoreClickActions, val -> config.ignoreClickActions = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_bazaar"))
                        .description(OptionDescription.of(translate("opt.ignore_bazaar.desc")))
                        .binding(true, () -> config.ignoreBazaarAndBin, val -> config.ignoreBazaarAndBin = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_uuids"))
                        .description(OptionDescription.of(translate("opt.ignore_uuids.desc")))
                        .binding(true, () -> config.ignoreUuidsAndIds, val -> config.ignoreUuidsAndIds = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_urls"))
                        .description(OptionDescription.of(translate("opt.ignore_urls.desc")))
                        .binding(true, () -> config.ignoreUrlsAndIps, val -> config.ignoreUrlsAndIps = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(translate("opt.ignore_dates"))
                        .description(OptionDescription.of(translate("opt.ignore_dates.desc")))
                        .binding(true, () -> config.ignoreDatesAndLobbies, val -> config.ignoreDatesAndLobbies = val)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .build())
                .build())
            
            
            .category(ConfigCategory.createBuilder()
                .name(translate("cat.cache"))
                .tooltip(translate("cat.cache.tooltip"))
                .group(OptionGroup.createBuilder()
                    .name(translate("group.actions"))
                    .option(ButtonOption.createBuilder()
                        .name(translate("opt.clear_cache"))
                        .description(OptionDescription.of(translate("opt.clear_cache.desc")))
                        .action((opt, btn) -> {
                            TranslationCache.clear();
                            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                            if (client.player != null) {
                                //? if <26.1 {
                                client.player.displayClientMessage(Component.literal("§a[SkyBlock Translator] Translation cache cleared successfully! / Кэш переводов успешно очищен!"), false);
                                //?} else
                                /*client.player.sendSystemMessage(Component.literal("§a[SkyBlock Translator] Translation cache cleared successfully! / Кэш переводов успешно очищен!"));*/
                            }
                        })
                        .build())
                    .option(ButtonOption.createBuilder()
                        .name(translate("opt.reload"))
                        .description(OptionDescription.of(translate("opt.reload.desc")))
                        .action((opt, btn) -> {
                            Minecraft client = Minecraft.getInstance();
                            Runnable reload = () -> {
                                TranslatorConfigManager.load();
                                TranslatorModClient.reloadTranslations();
                                client.setScreen(createScreen(parent));
                            };
                            if (opt.pendingChanges()) {
                                client.setScreen(new ConfirmScreen(confirmed -> {
                                    if (confirmed) reload.run();
                                    else client.setScreen(opt);
                                }, translate("opt.reload"), translate("opt.reload_discard")));
                            } else {
                                reload.run();
                            }
                        })
                        .build())
                    .option(ButtonOption.createBuilder()
                        .name(translate("dictionary.test"))
                        .action((screen, button) -> {
                            Minecraft client = Minecraft.getInstance();
                            client.setScreen(new DictionaryCheckScreen(screen));
                        }).build())
                    .option(ButtonOption.createBuilder()
                        .name(translate("opt.report"))
                        .description(OptionDescription.of(translate("opt.report.desc")))
                        .action((screen, option) -> ConfirmLinkScreen.confirmLinkNow(screen, ISSUES_PAGE))
                        .build())
                    .option(ButtonOption.createBuilder()
                        .name(translate("opt.suggest_translation"))
                        .description(OptionDescription.of(translate("opt.suggest_translation.desc")))
                        .action((screen, option) -> ConfirmLinkScreen.confirmLinkNow(screen, ISSUES_PAGE))
                        .build())
                    .build())
                .build())
            .build();

        return configLib.generateScreen(parent);
    }

    private record ApiKeyField(String key, boolean revealed) {
        @Override
        public String toString() {
            return "API key field";
        }
    }

    private record ApiKeyController(Option<ApiKeyField> option) implements IStringController<ApiKeyField> {
        @Override
        public String getString() {
            ApiKeyField field = option.pendingValue();
            return field.revealed() || field.key().isEmpty() ? field.key() : MASKED_API_KEY;
        }

        @Override
        public boolean isInputValid(String input) {
            return option.pendingValue().revealed();
        }

        @Override
        public void setFromString(String value) {
            if (isInputValid(value)) option.requestSet(new ApiKeyField(value, true));
        }
    }
}
