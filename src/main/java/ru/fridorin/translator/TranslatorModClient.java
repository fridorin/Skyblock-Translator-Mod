package ru.fridorin.translator;

import ru.fridorin.translator.cache.TranslationCache;
import ru.fridorin.translator.config.TranslatorConfig;
import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.service.TranslationService;
import ru.fridorin.translator.service.TranslationScope;
import ru.fridorin.translator.gui.OutgoingDraftScreen;
import net.minecraft.client.gui.screens.Screen;
import ru.fridorin.translator.service.SkyblockDictionary;
import ru.fridorin.translator.service.SpamFilter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TranslatorModClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranslatorModClient.class);
    private static final ThreadPoolExecutor DICTIONARY_LOADER = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "SkyblockTranslator-DictionaryLoader");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    // Fast, simple line-level cache to replace the complex tooltip cache
    private static final com.google.common.cache.Cache<String, Component> COMPONENT_CACHE = com.google.common.cache.CacheBuilder.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build();
    private static final com.google.common.cache.Cache<String, Object> PENDING_COMPONENTS = com.google.common.cache.CacheBuilder.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(10, TimeUnit.SECONDS)
        .build();

    private static final com.google.common.cache.Cache<String, Boolean> UNTRANSLATED_COMPONENTS = com.google.common.cache.CacheBuilder.newBuilder()
        .maximumSize(2000).expireAfterWrite(1, TimeUnit.SECONDS).build();
    private static OutgoingChatDraft outgoingDraft = new OutgoingChatDraft();
    private static ClientPacketListener chatConnection;
    private static ClientLevel chatLevel;

    // Precompiled regex for detecting public chat messages with any Hypixel rank (e.g. [VIP+], [MVP++], [HELPER])
    private static final Pattern PUBLIC_CHAT_PATTERN = Pattern.compile("^(?:\\[[A-Za-z0-9+ ]+\\] )?\\w+: .*");
    
    private static final java.util.concurrent.atomic.AtomicLong CACHE_GENERATION = new java.util.concurrent.atomic.AtomicLong();

    public static long getCacheGeneration() { return CACHE_GENERATION.get(); }

    public static void clearComponentCache() {
        CACHE_GENERATION.incrementAndGet();
        COMPONENT_CACHE.invalidateAll();
        PENDING_COMPONENTS.invalidateAll();
        UNTRANSLATED_COMPONENTS.invalidateAll();
    }

    private static KeyMapping keyOpenSettings;
    private static KeyMapping keyToggleTranslator;
    private static int ticksElapsed = 0;
    private static volatile long requestedDictionaryRevision;
    private static volatile long loadedDictionaryRevision = -1;
    private static volatile long failedDictionaryRevision = -1;

    public static KeyMapping getKeyOpenSettings() { return keyOpenSettings; }
    public static KeyMapping getKeyToggleTranslator() { return keyToggleTranslator; }

    @Override
    public void onInitializeClient() {
        TranslatorConfigManager.init();
        reloadTranslations();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DICTIONARY_LOADER.shutdownNow();
            clearComponentCache();
            TranslationService.shutdown();
            TranslationCache.shutdown();
        }));

        net.minecraft.client.KeyMapping.Category category = net.minecraft.client.KeyMapping.Category.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("skyblock_translator", "keys")
        );

        keyOpenSettings = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.skyblock_translator.settings",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            category
        ));

        keyToggleTranslator = KeyBindingHelper.registerKeyBinding(createToggleKey(category));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (repairLegacyToggleKey(keyToggleTranslator)) {
                KeyMapping.resetMapping();
                client.options.save();
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            updateChatSession(client);
            if (++ticksElapsed >= 100) {
                ticksElapsed = 0;
                TranslationService.updateOnlinePlayerNames(client);
            }

            while (keyOpenSettings.consumeClick()) {
                client.setScreen(ru.fridorin.translator.gui.TranslatorConfigScreen.createScreen(null));
            }
            while (keyToggleTranslator.consumeClick()) {
                TranslatorConfig config = TranslatorConfigManager.getConfig();
                config.enabled = !config.enabled;
                TranslatorConfigManager.save();
                if (client.player != null) {
                    //? if <26.1 {
                    client.player.displayClientMessage(Component.literal("§a[SkyBlock Translator] " + (config.enabled ? "Enabled" : "Disabled")), true);
                    //?} else
                    /*client.gui.setOverlayMessage(Component.literal("§a[SkyBlock Translator] " + (config.enabled ? "Enabled" : "Disabled")), false);*/
                }
            }
        });

        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (isTranslationDisabled() || !TranslatorConfigManager.getConfig().translateChat) return true;
            return handleIncomingChatEvent(message);
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;
            if (isTranslationDisabled() || !TranslatorConfigManager.getConfig().translateChat) return true;
            return handleIncomingChatEvent(message);
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (TranslationService.isBypassed() || message.startsWith("/")) return true;

            Minecraft client = Minecraft.getInstance();
            updateChatSession(client);
            var connection = client.getConnection();
            if (connection == null) return true;
            if (!outgoingTranslationEnabled() || !TranslationService.containsNonEnglishLetters(message)) return true;

            var draft = outgoingDraft;
            if (!draft.begin(message)) {
                showDraftNotice(client, "busy");
            } else {
                TranslationService.translateOutgoingAsync(message).whenComplete((result, error) -> client.schedule(() -> {
                    if (client.getConnection() != connection || draft != outgoingDraft) return;
                    draft.complete(error == null ? result : null);
                    if (currentScreen(client) == null) openOutgoingDraft(client);
                    else showDraftNotice(client, "ready");
                }));
            }
            return false;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var command = ClientCommandManager.literal("translator")
                .then(ClientCommandManager.literal("toggle")
                    .then(ClientCommandManager.literal("chat")
                        .executes(context -> {
                            TranslatorConfig config = TranslatorConfigManager.getConfig();
                            config.translateChat = !config.translateChat;
                            TranslatorConfigManager.save();
                            context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Chat translation: " + (config.translateChat ? "ON" : "OFF")));
                            return 1;
                        }))
                    .then(ClientCommandManager.literal("tooltips")
                        .executes(context -> {
                            TranslatorConfig config = TranslatorConfigManager.getConfig();
                            config.translateTooltips = !config.translateTooltips;
                            TranslatorConfigManager.save();
                            context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Tooltips translation: " + (config.translateTooltips ? "ON" : "OFF")));
                            return 1;
                        }))
                    .then(ClientCommandManager.literal("titles")
                        .executes(context -> {
                            TranslatorConfig config = TranslatorConfigManager.getConfig();
                            config.translateInventoryTitles = !config.translateInventoryTitles;
                            TranslatorConfigManager.save();
                            context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Inventory titles translation: " + (config.translateInventoryTitles ? "ON" : "OFF")));
                            return 1;
                        }))
                    .then(ClientCommandManager.literal("scoreboard")
                        .executes(context -> {
                            TranslatorConfig config = TranslatorConfigManager.getConfig();
                            config.translateScoreboard = !config.translateScoreboard;
                            TranslatorConfigManager.save();
                            context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Scoreboard translation: " + (config.translateScoreboard ? "ON" : "OFF")));
                            return 1;
                        }))
                    .then(ClientCommandManager.literal("npc")
                        .executes(context -> {
                            TranslatorConfig config = TranslatorConfigManager.getConfig();
                            config.translateNpcDialogues = !config.translateNpcDialogues;
                            TranslatorConfigManager.save();
                            context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] NPC dialogues translation: " + (config.translateNpcDialogues ? "ON" : "OFF")));
                            return 1;
                        }))
                    .then(ClientCommandManager.literal("outgoing")
                        .executes(context -> {
                            TranslatorConfig config = TranslatorConfigManager.getConfig();
                            config.translateOutgoingChat = !config.translateOutgoingChat;
                            TranslatorConfigManager.save();
                            context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Outgoing chat translation: " + (config.translateOutgoingChat ? "ON" : "OFF")));
                            return 1;
                        }))
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] Use: /translator toggle [chat|tooltips|titles|scoreboard|npc|outgoing]"));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("setlang")
                    .then(ClientCommandManager.argument("lang", StringArgumentType.string())
                        .executes(context -> {
                            String requestedLanguage = StringArgumentType.getString(context, "lang");
                            String lang = TranslatorConfigManager.normalizeLanguage(requestedLanguage);
                            if (!lang.equalsIgnoreCase(requestedLanguage.trim())) {
                                context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] Invalid language code (example: ru, fr, zh-cn)."));
                                return 0;
                            }
                            TranslatorConfig config = TranslatorConfigManager.getConfig();
                            config.targetLanguage = lang;
                            TranslatorConfigManager.save();
                            reloadTranslations();
                            context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Target language set to: " + lang));
                            return 1;
                        }))
                )
                .then(ClientCommandManager.literal("setprovider")
                    .then(ClientCommandManager.argument("provider", StringArgumentType.string())
                        .executes(context -> {
                            String provider = StringArgumentType.getString(context, "provider").toUpperCase(java.util.Locale.ROOT);
                            if (provider.equals("GOOGLE_FREE") || provider.equals("DEEPL_FREE") || provider.equals("YANDEX")) {
                                TranslatorConfig config = TranslatorConfigManager.getConfig();
                                config.apiProvider = provider;
                                TranslatorConfigManager.save();
                                context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] API Provider set to: " + provider));
                            } else {
                                context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] Invalid provider. Valid options: GOOGLE_FREE, DEEPL_FREE, YANDEX"));
                            }
                            return 1;
                        }))
                )
                .then(ClientCommandManager.literal("clear-cache")
                    .executes(context -> {
                        TranslationCache.clear();
                        clearComponentCache();
                        context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Translation cache cleared."));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("reload")
                    .executes(context -> {
                        TranslatorConfigManager.load();
                        reloadTranslations();
                        context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Dictionary and cache reload requested."));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("pair", StringArgumentType.greedyString())
                        .executes(context -> {
                            String pair = StringArgumentType.getString(context, "pair");
                            int eqIdx = pair.indexOf('=');
                            if (eqIdx == -1) {
                                context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] Format must be: /translator add <phrase> = <translation>"));
                                return 1;
                            }
                            String english = pair.substring(0, eqIdx).trim();
                            String translation = pair.substring(eqIdx + 1).trim();
                            if (english.isEmpty() || translation.isEmpty()) {
                                context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] Phrase and translation cannot be empty!"));
                                return 1;
                            }
                            if (!SkyblockDictionary.addUserTranslation(english, translation)) {
                                context.getSource().sendFeedback(Component.translatable("gui.skyblock_translator.dictionary.save_failed")
                                        .withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Added translation mapping: '" + english + "' -> '" + translation + "'"));
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("lookup")
                    .then(ClientCommandManager.argument("phrase", StringArgumentType.greedyString())
                        .executes(context -> {
                            String phrase = StringArgumentType.getString(context, "phrase");
                            String translation = SkyblockDictionary.getTranslationOrUserTranslation(phrase);
                            if (translation != null) {
                                context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] '" + phrase + "' = " + translation));
                            } else {
                                context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] Phrase '" + phrase + "' not found in dictionaries."));
                            }
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("copy-hand")
                    .executes(context -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.player == null) {
                            context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] You must be in-game to use this command!"));
                            return 1;
                        }
                        net.minecraft.world.item.ItemStack itemInHand = client.player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
                        if (itemInHand.isEmpty()) {
                            context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] Hold an item in your main hand first!"));
                            return 1;
                        }
                        
                        var rawLines = TranslationService.callWithBypass(() -> itemInHand.getTooltipLines(
                                net.minecraft.world.item.Item.TooltipContext.of(client.level),
                                client.player,
                                net.minecraft.world.item.TooltipFlag.Default.NORMAL));

                        if (rawLines == null || rawLines.isEmpty()) {
                            context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] No tooltip lines found on this item!"));
                            return 1;
                        }

                        java.util.Map<String, String> template = new java.util.LinkedHashMap<>();
                        for (Component line : rawLines) {
                            String plain = line.getString();
                            String clean = TranslationService.stripFormatting(plain).trim();
                            if (!clean.isEmpty()) {
                                template.put(clean.toLowerCase(java.util.Locale.ROOT), "");
                            }
                        }
                        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(template);
                        client.keyboardHandler.setClipboard(json);
                        context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Item translation JSON template copied to clipboard!"));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("update")
                    .executes(context -> {
                        TranslatorConfig config = TranslatorConfigManager.getConfig();
                        context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Fetching latest dictionary from GitHub..."));
                        SkyblockDictionary.downloadDictionaryAsync(config.targetLanguage).thenAccept(size -> {
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> {
                                if (size >= 0) {
                                    context.getSource().sendFeedback(Component.literal("§a[SkyBlock Translator] Dictionary updated! Loaded " + size + " entries from GitHub."));
                                } else {
                                    context.getSource().sendFeedback(Component.literal("§c[SkyBlock Translator] Failed to update dictionary from GitHub. Check your internet connection or repository settings."));
                                }
                            });
                        });
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("status").executes(context -> {
                    TranslatorConfig config = TranslatorConfigManager.getConfig();
                    long revision = requestedDictionaryRevision;
                    boolean loaded = loadedDictionaryRevision == revision;
                    String dictionaryState = "loading";
                    if (loaded) dictionaryState = "loaded";
                    else if (failedDictionaryRevision == revision) dictionaryState = "failed";
                    context.getSource().sendFeedback(Component.translatable("gui.skyblock_translator.status",
                            config.targetLanguage, config.apiProvider,
                            Component.translatable(config.localOnly ? "gui.skyblock_translator.mode.local" : "gui.skyblock_translator.mode.online"),
                            loaded ? SkyblockDictionary.entryCount() : 0,
                            Component.translatable("gui.skyblock_translator.status." + dictionaryState),
                            TranslationService.pendingRequests(), outgoingDraft.pendingCount(), TranslationService.failureLabel(TranslationService.lastError())));
                    return 1;
                }))
                .then(ClientCommandManager.literal("draft").executes(context -> {
                    Minecraft client = Minecraft.getInstance();
                    client.schedule(() -> {
                        if (!openOutgoingDraft(client)) showDraftNotice(client, "empty");
                    });
                    return 1;
                }))
                .then(ClientCommandManager.literal("test").executes(context -> {
                    TranslatorConfig config = TranslatorConfigManager.getConfig();
                    String provider = config.apiProvider;
                    Minecraft client = Minecraft.getInstance();
                    TranslationService.testConnectionAsync(provider, config.keyFor(provider), config.localOnly)
                            .thenAccept(result -> client.schedule(() -> context.getSource().sendFeedback(
                                    Component.translatable("gui.skyblock_translator.provider.result", provider, TranslationService.failureLabel(result)))));
                    return 1;
                }))
                .executes(context -> {
                    Minecraft client = Minecraft.getInstance();
                    client.execute(() -> {
                        client.setScreen(ru.fridorin.translator.gui.TranslatorConfigScreen.createScreen(null));
                    });
                    return 1;
                });

            dispatcher.register(command);

            dispatcher.register(ClientCommandManager.literal("переводчик")
                .redirect(dispatcher.getRoot().getChild("translator"))
            );
        });
    }

    public static String componentToLegacy(Component component) {
        if (component == null) return "";
        StringBuilder sb = new StringBuilder();
        component.visit((style, content) -> {
            sb.append(styleToLegacyCodes(style)).append(content);
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    public static String styleToLegacyCodes(Style style) {
        if (style == null || style.isEmpty()) return "§r";
        StringBuilder sb = new StringBuilder("§r");
        if (style.getColor() != null) {
            String hex = style.getColor().serialize();
            if (hex.startsWith("#")) {
                sb.append("§x");
                for (char c : hex.substring(1).toCharArray()) {
                    sb.append("§").append(c);
                }
            } else {
                String colorStr = hex.toLowerCase(java.util.Locale.ROOT);
                switch (colorStr) {
                    case "black": sb.append("§0"); break;
                    case "dark_blue": sb.append("§1"); break;
                    case "dark_green": sb.append("§2"); break;
                    case "dark_aqua": sb.append("§3"); break;
                    case "dark_red": sb.append("§4"); break;
                    case "dark_purple": sb.append("§5"); break;
                    case "gold": sb.append("§6"); break;
                    case "gray": sb.append("§7"); break;
                    case "dark_gray": sb.append("§8"); break;
                    case "blue": sb.append("§9"); break;
                    case "green": sb.append("§a"); break;
                    case "aqua": sb.append("§b"); break;
                    case "red": sb.append("§c"); break;
                    case "light_purple": sb.append("§d"); break;
                    case "yellow": sb.append("§e"); break;
                    case "white": sb.append("§f"); break;
                }
            }
        }
        if (style.isBold()) sb.append("§l");
        if (style.isItalic()) sb.append("§o");
        if (style.isUnderlined()) sb.append("§n");
        if (style.isStrikethrough()) sb.append("§m");
        if (style.isObfuscated()) sb.append("§k");
        return sb.toString();
    }

    private static void updateChatSession(Minecraft client) {
        var connection = client.getConnection();
        if (chatConnection == connection && chatLevel == client.level) return;
        if (chatConnection != connection) outgoingDraft = new OutgoingChatDraft();
        chatConnection = connection;
        chatLevel = client.level;
        ticksElapsed = 0;
        clearComponentCache();
        TranslationService.updateOnlinePlayerNames(client);
    }

    private static boolean outgoingTranslationEnabled() {
        TranslatorConfig config = TranslatorConfigManager.getConfig();
        return !isTranslationDisabled() && config.translateOutgoingChat && TranslationScope.OUTGOING.allowsOnline(config);
    }

    public static Screen currentScreen(Minecraft client) {
        //? if <26.2 {
        return client.screen;
        //?} else
        /*return client.gui.screen();*/
    }

    private static boolean openOutgoingDraft(Minecraft client) {
        updateChatSession(client);
        var connection = client.getConnection();
        var draft = outgoingDraft;
        if (connection == null || draft.text() == null) return false;
        client.setScreen(new OutgoingDraftScreen(currentScreen(client), draft.text(), toSend -> {
            if (client.getConnection() != connection || draft != outgoingDraft) return false;
            return draft.use(toSend, message -> TranslationService.runWithBypass(() -> connection.sendChat(message)));
        }, OutgoingChatDraft::isValidChat, draft::edit, draft::discard));
        return true;
    }

    private static void showDraftNotice(Minecraft client, String suffix) {
        if (client.player == null) return;
        Component message = Component.translatable("gui.skyblock_translator.draft." + suffix);
        //? if <26.1 {
        client.player.displayClientMessage(message, false);
        //?} else
        /*client.player.sendSystemMessage(message);*/
    }

    static KeyMapping createToggleKey(KeyMapping.Category category) {
        return new KeyMapping("key.skyblock_translator.toggle", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), category);
    }

    static boolean repairLegacyToggleKey(KeyMapping mapping) {
        // 26.3 can read the old GLFW value but cannot poll its negative SDL scancode.
        if (!"key.keyboard.-1".equals(mapping.saveString())) return false;
        mapping.setKey(InputConstants.UNKNOWN);
        return true;
    }

    /** Called by the client thread after loading config or selecting a target language. */
    public static void reloadTranslations() {
        String language = TranslatorConfigManager.getConfig().targetLanguage;
        long revision = ++requestedDictionaryRevision;
        clearComponentCache();
        DICTIONARY_LOADER.execute(() -> {
            try {
                SkyblockDictionary.loadForLanguage(language);
                SkyblockDictionary.loadUserDictionary();
                TranslationCache.loadForLanguage(language);
                loadedDictionaryRevision = revision;
            } catch (Exception e) {
                failedDictionaryRevision = revision;
                LOGGER.error("Failed to load dictionaries and cache", e);
            }
        });
    }

    public static Component legacyToComponent(String text) {
        if (text == null || text.isEmpty()) return Component.empty();

        MutableComponent root = Component.empty();
        StringBuilder currentText = new StringBuilder();
        Style currentStyle = Style.EMPTY;

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                char code = text.charAt(i + 1);

                if (currentText.length() > 0) {
                    root.append(Component.literal(currentText.toString()).setStyle(currentStyle));
                    currentText.setLength(0);
                }

                if ((code == 'x' || code == 'X') && i + 13 < text.length() && 
                    text.charAt(i + 2) == '§' && text.charAt(i + 4) == '§' && 
                    text.charAt(i + 6) == '§' && text.charAt(i + 8) == '§' && 
                    text.charAt(i + 10) == '§' && text.charAt(i + 12) == '§') {

                    StringBuilder hex = new StringBuilder("#");
                    hex.append(text.charAt(i + 3));
                    hex.append(text.charAt(i + 5));
                    hex.append(text.charAt(i + 7));
                    hex.append(text.charAt(i + 9));
                    hex.append(text.charAt(i + 11));
                    hex.append(text.charAt(i + 13));

                    try {
                        TextColor textColor = TextColor.parseColor(hex.toString()).getOrThrow();
                        currentStyle = Style.EMPTY.withColor(textColor);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to parse hex color: {}", hex, e);
                    }
                    i += 14;
                    continue;
                }

                char lowerCode = Character.toLowerCase(code);
                switch (lowerCode) {
                    case '0': currentStyle = Style.EMPTY.withColor(ChatFormatting.BLACK); break;
                    case '1': currentStyle = Style.EMPTY.withColor(ChatFormatting.DARK_BLUE); break;
                    case '2': currentStyle = Style.EMPTY.withColor(ChatFormatting.DARK_GREEN); break;
                    case '3': currentStyle = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA); break;
                    case '4': currentStyle = Style.EMPTY.withColor(ChatFormatting.DARK_RED); break;
                    case '5': currentStyle = Style.EMPTY.withColor(ChatFormatting.DARK_PURPLE); break;
                    case '6': currentStyle = Style.EMPTY.withColor(ChatFormatting.GOLD); break;
                    case '7': currentStyle = Style.EMPTY.withColor(ChatFormatting.GRAY); break;
                    case '8': currentStyle = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY); break;
                    case '9': currentStyle = Style.EMPTY.withColor(ChatFormatting.BLUE); break;
                    case 'a': currentStyle = Style.EMPTY.withColor(ChatFormatting.GREEN); break;
                    case 'b': currentStyle = Style.EMPTY.withColor(ChatFormatting.AQUA); break;
                    case 'c': currentStyle = Style.EMPTY.withColor(ChatFormatting.RED); break;
                    case 'd': currentStyle = Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE); break;
                    case 'e': currentStyle = Style.EMPTY.withColor(ChatFormatting.YELLOW); break;
                    case 'f': currentStyle = Style.EMPTY.withColor(ChatFormatting.WHITE); break;
                    case 'k': currentStyle = currentStyle.withObfuscated(true); break;
                    case 'l': currentStyle = currentStyle.withBold(true); break;
                    case 'm': currentStyle = currentStyle.withStrikethrough(true); break;
                    case 'n': currentStyle = currentStyle.withUnderlined(true); break;
                    case 'o': currentStyle = currentStyle.withItalic(true); break;
                    case 'r': currentStyle = Style.EMPTY; break;
                }
                i += 2;
            } else {
                currentText.append(c);
                i++;
            }
        }

        if (currentText.length() > 0) {
            root.append(Component.literal(currentText.toString()).setStyle(currentStyle));
        }

        return root;
    }

    public static Component translateComponent(Component component) {
        return translateComponent(component, TranslationScope.WORLD);
    }

    public static Component translateComponent(Component component, TranslationScope scope) {
        return translateComponentWithIgnore(component, null, scope);
    }

    public static Component translateComponentWithIgnore(Component component, String ignoreText) {
        return translateComponentWithIgnore(component, ignoreText, TranslationScope.WORLD);
    }

    private static Component translateComponentWithIgnore(Component component, String ignoreText, TranslationScope scope) {
        if (component == null || isTranslationDisabled()) return component;
        String plain = component.getString().strip();
        if (!isTranslatable(plain) || plain.equalsIgnoreCase(ignoreText)) return component;
        Style interactions = ChatPresentation.commonInteractions(component);
        if (interactions == null) return component;

        String legacyText = componentToLegacy(component);
        if (legacyText.isEmpty()) {
            return component;
        }

        int leadingSpaces = 0;
        while (leadingSpaces < legacyText.length() && legacyText.charAt(leadingSpaces) == ' ') {
            leadingSpaces++;
        }
        int trailingSpaces = 0;
        while (trailingSpaces < legacyText.length() && legacyText.charAt(legacyText.length() - 1 - trailingSpaces) == ' ') {
            trailingSpaces++;
        }
        String trimmed = legacyText.trim();

        final long generation = getCacheGeneration();
        final String cacheKey = generation + ":" + scope + ":" + legacyText;
        Component cached = COMPONENT_CACHE.getIfPresent(cacheKey);
        if (cached != null) return preserveInteractions(cached, interactions);
        if (UNTRANSLATED_COMPONENTS.getIfPresent(cacheKey) != null || PENDING_COMPONENTS.getIfPresent(cacheKey) != null) return component;

        String immediate = TranslationService.getImmediateTranslation(trimmed, scope);
        if (immediate != null) {
            if (TranslationService.isTranslationSame(trimmed, immediate)) {
                UNTRANSLATED_COMPONENTS.put(cacheKey, true);
                return component;
            }
            String restored = " ".repeat(leadingSpaces) + immediate + " ".repeat(trailingSpaces);
            Component translatedComponent = legacyToComponent(restored);
            COMPONENT_CACHE.put(cacheKey, translatedComponent);
            return preserveInteractions(translatedComponent, interactions);
        }

        final int finalLeadingSpaces = leadingSpaces;
        final int finalTrailingSpaces = trailingSpaces;

        Object pending = new Object();
        if (PENDING_COMPONENTS.asMap().putIfAbsent(cacheKey, pending) != null) return component;
        TranslationService.translateAsync(trimmed, scope).thenAccept(translatedLegacy -> {
            if (generation != getCacheGeneration()) return;
            if (translatedLegacy != null && !translatedLegacy.trim().isEmpty() && !translatedLegacy.equals(trimmed)) {
                String restored = " ".repeat(finalLeadingSpaces) + translatedLegacy + " ".repeat(finalTrailingSpaces);
                COMPONENT_CACHE.put(cacheKey, legacyToComponent(restored));
            } else {
                UNTRANSLATED_COMPONENTS.put(cacheKey, true);
            }
        }).whenComplete((result, error) -> PENDING_COMPONENTS.asMap().remove(cacheKey, pending));
        
        return component;
    }

    private static CompletableFuture<Component> translateComponentAsync(Component component, TranslationScope scope) {
        if (component == null || isTranslationDisabled() || !isTranslatable(component.getString())) {
            return CompletableFuture.completedFuture(component);
        }

        String legacyText = componentToLegacy(component);
        if (legacyText.isEmpty()) {
            return CompletableFuture.completedFuture(component);
        }

        int leadingSpaces = 0;
        while (leadingSpaces < legacyText.length() && legacyText.charAt(leadingSpaces) == ' ') {
            leadingSpaces++;
        }
        int trailingSpaces = 0;
        while (trailingSpaces < legacyText.length() && legacyText.charAt(legacyText.length() - 1 - trailingSpaces) == ' ') {
            trailingSpaces++;
        }
        String trimmed = legacyText.trim();

        final long generation = getCacheGeneration();
        final String cacheKey = generation + ":" + scope + ":" + legacyText;
        Component cached = COMPONENT_CACHE.getIfPresent(cacheKey);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        String immediate = TranslationService.getImmediateTranslation(trimmed, scope);
        if (immediate != null) {
            String restored = " ".repeat(leadingSpaces) + immediate + " ".repeat(trailingSpaces);
            return CompletableFuture.completedFuture(legacyToComponent(restored));
        }

        final int finalLeadingSpaces = leadingSpaces;
        final int finalTrailingSpaces = trailingSpaces;

        return TranslationService.translateAsync(trimmed, scope).thenApply(translatedLegacy -> {
            if (translatedLegacy == null || translatedLegacy.trim().isEmpty() || translatedLegacy.equals(trimmed)) {
                return component;
            }
            String restored = " ".repeat(finalLeadingSpaces) + translatedLegacy + " ".repeat(finalTrailingSpaces);
            return legacyToComponent(restored);
        });
    }

    private static boolean isTranslatable(String text) {
        return TranslationService.containsLetters(text) && !TranslationService.containsNonEnglishLetters(text);
    }

    private static Component preserveInteractions(Component translated, Style interactions) {
        return interactions.isEmpty() ? translated : Component.empty().setStyle(interactions).append(translated);
    }

    private boolean handleIncomingChatEvent(Component message) {
        String rawText = message.getString();
        if (rawText == null || rawText.trim().isEmpty()) return true;

        if (SpamFilter.shouldIgnore(rawText)) {
            return true;
        }
        if (TranslationService.containsNonEnglishLetters(rawText)) {
            return true;
        }

        TranslatorConfig config = TranslatorConfigManager.getConfig();

        boolean shouldTranslate = true;
        String cleanText = TranslationService.stripFormatting(rawText).trim();
        
        if (cleanText.startsWith("Guild >") || cleanText.startsWith("G >") || cleanText.startsWith("Officer >") || cleanText.startsWith("O >")) {
            shouldTranslate = config.translateGuildChat;
        } else if (cleanText.startsWith("Party >") || cleanText.startsWith("P >")) {
            shouldTranslate = config.translatePartyChat;
        } else if (cleanText.startsWith("Co-op >") || cleanText.startsWith("C >")) {
            shouldTranslate = config.translateCoopChat;
        } else if (cleanText.startsWith("From ") || cleanText.startsWith("To ")) {
            shouldTranslate = config.translateDirectMessages;
        } else if (cleanText.startsWith("[NPC]")) {
            shouldTranslate = config.translateNpcDialogues;
        } else if (PUBLIC_CHAT_PATTERN.matcher(cleanText).matches()) {
            shouldTranslate = config.translatePublicChat;
        } else {
            shouldTranslate = config.translateSystemMessages;
        }

        if (!shouldTranslate) {
            return true;
        }

        Minecraft chatClient = Minecraft.getInstance();
        updateChatSession(chatClient);
        var connection = chatClient.getConnection();
        var level = chatClient.level;
        long chatGeneration = getCacheGeneration();
        translateComponentAsync(message, cleanText.startsWith("[NPC]") ? TranslationScope.NPC : TranslationScope.CHAT).thenAccept(translated -> {
            if (translated == message || translated == null
                    || TranslationService.isTranslationSame(rawText, translated.getString())) return;

            // Immediate dictionary matches still have to wait for vanilla to insert the original.
            chatClient.schedule(() -> {
                if (chatClient.getConnection() != connection || chatClient.level != level
                        || chatGeneration != getCacheGeneration() || !config.enabled || !config.translateChat) return;
                if (chatClient.gui != null && chatClient.gui.getChat() instanceof ChatUpdateExtension chat) {
                    Component displayed = ChatPresentation.format(message, translated, config.chatDisplayMode,
                            config.targetLanguage, config.showHoverOriginal);
                    chat.skyblock_translator$updateMessage(message, displayed);
                }
            });
        });

        return true;
    }

    public static boolean isHypixelAddress(String address) {
        if (address == null) return false;
        String host = address.trim().toLowerCase(java.util.Locale.ROOT).replaceFirst(":\\d+$", "");
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net")
                || host.equals("hypixel.io") || host.endsWith(".hypixel.io");
    }

    public static boolean isTranslationDisabled() {
        TranslatorConfig config = TranslatorConfigManager.getConfig();
        if (loadedDictionaryRevision != requestedDictionaryRevision || !config.enabled
                || TranslationService.isBypassed()
                || "en".equalsIgnoreCase(config.targetLanguage)) return true;
        Minecraft client = Minecraft.getInstance();
        if (client == null || !client.isSameThread()) return true;
        if (config.onlyOnHypixel) {
            var server = client.getCurrentServer();
            if (server == null || !isHypixelAddress(server.ip)) return true;
        }
        return config.disableOnShift && client.hasShiftDown();
    }
}
