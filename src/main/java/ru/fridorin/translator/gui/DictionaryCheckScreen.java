package ru.fridorin.translator.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DictionaryCheckScreen extends Screen {
    private final Screen parent;
    private String sample = "";

    DictionaryCheckScreen(Screen parent) {
        super(Component.translatable("gui.skyblock_translator.dictionary.test"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(376, width - 24);
        int x = (width - fieldWidth) / 2;
        int y = height / 2;
        Component label = Component.translatable("gui.skyblock_translator.dictionary.sample");
        addRenderableOnly(new StringWidget(x, y - 30, fieldWidth, 20, label, font));
        EditBox input = addRenderableWidget(new EditBox(font, x, y - 4, fieldWidth, 20, label));
        input.setMaxLength(4096);
        input.setValue(sample);
        input.setResponder(value -> sample = value);
        addRenderableWidget(Button.builder(title, button -> TranslatorConfigScreen.inspectDictionary(this, sample))
                .bounds(x, y + 26, fieldWidth, 20).build());
        setInitialFocus(input);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
