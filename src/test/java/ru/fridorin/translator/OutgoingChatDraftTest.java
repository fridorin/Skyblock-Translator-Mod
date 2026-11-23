package ru.fridorin.translator;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class OutgoingChatDraftTest {
    @Test
    public void draftWaitsForExplicitConfirmationAndSendsTheEditedValue() {
        var draft = new OutgoingChatDraft();
        List<String> sent = new ArrayList<>();
        assertTrue(draft.begin("Привет"));
        assertFalse(draft.begin("Другое сообщение"));
        assertFalse(draft.use("premature send", sent::add));
        draft.complete("Hello");
        assertTrue(sent.isEmpty());
        assertEquals("Hello", draft.text());
        draft.edit("Hi there!");
        assertTrue(draft.use(draft.text(), sent::add));
        assertEquals(List.of("Hi there!"), sent);
        assertFalse(draft.use("repeat", sent::add));
    }

    @Test
    public void invalidTranslationsUseOriginalAndDiscardNeverSends() {
        var draft = new OutgoingChatDraft();
        for (String invalid : List.of("/help", "line\nbreak", "text§a", "text\u2028line", "x".repeat(257))) {
            assertTrue(draft.begin("Привет"));
            draft.complete(invalid);
            assertEquals("Привет", draft.text());
            assertFalse(draft.use(invalid, ignored -> fail()));
            draft.discard();
            assertEquals(0, draft.pendingCount());
        }
    }
}
