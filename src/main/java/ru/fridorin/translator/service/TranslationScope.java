package ru.fridorin.translator.service;

import ru.fridorin.translator.config.TranslatorConfig;

public enum TranslationScope {
    CHAT, NPC, TOOLTIP, WORLD, OUTGOING;

    public boolean allowsOnline(TranslatorConfig config) {
        if (config.localOnly) return false;
        return switch (this) {
            case CHAT, OUTGOING -> config.onlineChat;
            case NPC -> config.onlineNpc;
            case TOOLTIP -> config.onlineTooltips;
            case WORLD -> config.onlineWorld;
        };
    }

    public boolean mayPersist(TranslatorConfig config) {
        return switch (this) {
            case CHAT, NPC -> config.persistChatCache;
            case OUTGOING -> false;
            default -> true;
        };
    }
}
