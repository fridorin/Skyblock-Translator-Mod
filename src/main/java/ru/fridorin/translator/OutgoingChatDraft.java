package ru.fridorin.translator;

import net.minecraft.SharedConstants;
import net.minecraft.util.StringUtil;

/** One draft per connection. Accessed on the client thread. */
final class OutgoingChatDraft {
    private String original;
    private String text;

    boolean begin(String message) {
        if (original != null) return false;
        original = message;
        return true;
    }

    void complete(String translated) {
        if (original != null) text = isValidChat(translated) ? translated : original;
    }

    int pendingCount() {
        return original == null ? 0 : 1;
    }

    String text() { return text; }

    void edit(String value) {
        if (text != null) text = value;
    }

    boolean use(String message, java.util.function.Consumer<String> sender) {
        if (text == null || !isValidChat(message)) return false;
        discard();
        sender.accept(message);
        return true;
    }

    void discard() {
        original = null;
        text = null;
    }

    public static boolean isValidChat(String text) {
        return text != null && !text.isBlank() && !text.stripLeading().startsWith("/")
                && text.length() <= SharedConstants.MAX_CHAT_LENGTH
                && text.codePoints().allMatch(StringUtil::isAllowedChatCharacter)
                && text.codePoints().noneMatch(c -> c == 0x2028 || c == 0x2029);
    }
}
