package ru.fridorin.translator;

import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

final class ChatPresentation {
    private ChatPresentation() {}

    static Component format(Component original, Component translated, String mode, String language,
                            boolean showHoverOriginal) {
        MutableComponent translation = Component.empty().append(translated);
        if (showHoverOriginal) {
            translation.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                    Component.literal("Original: ").withStyle(ChatFormatting.GRAY).append(original))));
        }
        if ("REPLACE".equals(mode) && !hasInteractions(original)) return translation;

        // A neutral parent keeps the original's inherited styles on the original subtree.
        MutableComponent combined = Component.empty().append(original);
        if ("INLINE".equals(mode)) {
            return combined.append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                    .append(translation)
                    .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }

        TextColor color = original.visit((style, text) -> Optional.ofNullable(style.getColor()), Style.EMPTY)
                .orElse(TextColor.fromLegacyFormat(ChatFormatting.GRAY));
        String lineStart = "APPEND".equals(mode) ? "\n " : "\n";
        String prefix = lineStart + "↳ [" + language.toUpperCase(Locale.ROOT) + "] ";
        return combined.append(Component.literal(prefix).withStyle(style -> style.withColor(color)))
                .append(translation);
    }

    static boolean hasInteractions(Component component) {
        return component.visit((style, text) -> {
            if (style.getClickEvent() != null || style.getHoverEvent() != null || style.getInsertion() != null) {
                return Optional.of(true);
            }
            return Optional.<Boolean>empty();
        }, Style.EMPTY).orElse(false);
    }

    /** Returns null when applying one interaction to the translation would change its scope. */
    static Style commonInteractions(Component component) {
        Style[] common = {null};
        boolean mixed = component.visit((style, text) -> {
            if (text.isEmpty()) return Optional.<Boolean>empty();
            Style interactions = Style.EMPTY.withClickEvent(style.getClickEvent())
                    .withHoverEvent(style.getHoverEvent()).withInsertion(style.getInsertion());
            if (common[0] == null) common[0] = interactions;
            return common[0].equals(interactions) ? Optional.empty() : Optional.of(true);
        }, Style.EMPTY).orElse(false);
        if (mixed) return null;
        return common[0] == null ? Style.EMPTY : common[0];
    }
}
