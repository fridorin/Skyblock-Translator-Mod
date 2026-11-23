package ru.fridorin.translator;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.Test;

public class ChatPresentationTest {
    @Test
    public void originalInteractionsKeepTheirScopeInEveryMode() {
        ClickEvent click = new ClickEvent.SuggestCommand("/help");
        HoverEvent hover = new HoverEvent.ShowText(Component.literal("Example hover"));
        Style originalStyle = Style.EMPTY.withColor(ChatFormatting.AQUA)
                .withClickEvent(click).withHoverEvent(hover).withInsertion("insert");
        Component original = Component.literal("Example ").setStyle(originalStyle)
                .append(Component.literal("nested").withStyle(ChatFormatting.BOLD));
        Component translated = Component.literal("Пример");

        for (String mode : new String[]{"SEPARATE_LINE", "APPEND", "INLINE", "REPLACE"}) {
            Map<String, Style> styles = stylesOf(ChatPresentation.format(original, translated, mode, "ru", true));
            assertEquals(mode, originalStyle, styles.get("Example "));
            assertEquals(mode, click, styles.get("nested").getClickEvent());
            assertTrue(mode, styles.get("nested").isBold());
            assertNull(mode, styles.get("Пример").getClickEvent());
            assertNull(mode, styles.get("Пример").getInsertion());
            assertNotEquals(mode, hover, styles.get("Пример").getHoverEvent());
            assertNotNull(mode, styles.get("Пример").getHoverEvent());
        }
    }

    @Test
    public void replaceOmitsPlainOriginalAndKeepsTranslatedUnicodeAndLineBreaks() {
        Component rendered = ChatPresentation.format(Component.literal("Hello"),
                Component.literal("Привет!\nこんにちは"), "REPLACE", "ru", false);
        assertEquals("Привет!\nこんにちは", rendered.getString());
        assertEquals(Style.EMPTY, stylesOf(rendered).get("Привет!\nこんにちは"));
    }

    @Test
    public void mixedInteractionsCannotBeAppliedToTheWholeTranslation() {
        Component interactive = Component.literal("Owner").withStyle(style -> style.withInsertion("Owner"));
        Component original = Component.empty().append(interactive).append(Component.literal(" sent a message"));
        assertNull(ChatPresentation.commonInteractions(original));
        assertEquals("Owner", ChatPresentation.commonInteractions(interactive).getInsertion());
        assertEquals(Style.EMPTY, ChatPresentation.commonInteractions(Component.literal("Plain")));
    }

    private static Map<String, Style> stylesOf(Component component) {
        Map<String, Style> styles = new LinkedHashMap<>();
        component.visit((style, text) -> {
            styles.put(text, style);
            return Optional.empty();
        }, Style.EMPTY);
        return styles;
    }
}
