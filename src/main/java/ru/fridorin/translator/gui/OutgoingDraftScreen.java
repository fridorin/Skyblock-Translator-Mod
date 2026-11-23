package ru.fridorin.translator.gui;

import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class OutgoingDraftScreen extends Screen {
    private final Screen parent;
    private final Predicate<String> send;
    private final Predicate<String> valid;
    private final Consumer<String> edit;
    private final Runnable discard;
    private String text;

    public OutgoingDraftScreen(Screen parent, String text, Predicate<String> send, Predicate<String> valid,
                               Consumer<String> edit, Runnable discard) {
        super(label("title"));
        this.parent = parent;
        this.text = text;
        this.send = send;
        this.valid = valid;
        this.edit = edit;
        this.discard = discard;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(376, width - 24);
        int x = (width - fieldWidth) / 2;
        int y = height / 2;
        addRenderableOnly(new StringWidget(x, y - 30, fieldWidth, 20, title, font));
        EditBox input = addRenderableWidget(new EditBox(font, x, y - 4, fieldWidth, 20, title));
        input.setMaxLength(SharedConstants.MAX_CHAT_LENGTH);
        input.setValue(text);
        Button sendButton = addRenderableWidget(Button.builder(label("send"), button -> {
            if (send.test(input.getValue())) onClose();
            else button.active = false;
        }).bounds(width / 2 - 104, y + 26, 100, 20).build());
        sendButton.active = valid.test(text);
        input.setResponder(value -> {
            text = value;
            edit.accept(value);
            sendButton.active = valid.test(value);
        });
        addRenderableWidget(Button.builder(label("discard"), button -> {
            discard.run();
            onClose();
        }).bounds(width / 2 + 4, y + 26, 100, 20).build());
        setInitialFocus(input);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component label(String suffix) {
        return Component.translatable("gui.skyblock_translator.draft." + suffix);
    }
}
