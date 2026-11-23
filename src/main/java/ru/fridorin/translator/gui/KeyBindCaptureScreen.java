package ru.fridorin.translator.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;

public class KeyBindCaptureScreen extends Screen {
    private final Screen parent;
    private final KeyMapping keyMapping;

    private final java.util.function.Supplier<Screen> afterBinding;

    public KeyBindCaptureScreen(Screen parent, KeyMapping keyMapping, java.util.function.Supplier<Screen> afterBinding) {
        super(Component.translatable("gui.skyblock_translator.key_capture.title"));
        this.parent = parent;
        this.keyMapping = keyMapping;
        this.afterBinding = afterBinding;
    }

    @Override
    //? if <26.1 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    //?} else {
    /*public void extractRenderState(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    *///?}

        Component prompt = Component.translatable("gui.skyblock_translator.key_capture.prompt",
                Component.translatable(keyMapping.getName()));
        Component cancel = Component.translatable("gui.skyblock_translator.key_capture.cancel");
        graphics.drawCenteredString(this.font, prompt, this.width / 2, this.height / 2 - 20, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, cancel, this.width / 2, this.height / 2 + 10, 0xFFAAAAAA);

        //? if <26.1 {
        super.render(graphics, mouseX, mouseY, delta);
        //?} else
        /*super.extractRenderState(graphics, mouseX, mouseY, delta);*/
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == InputConstants.KEY_ESCAPE) {
            this.minecraft.setScreen(parent);
            return true;
        }
        
        bind(InputConstants.getKey(event));
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        bind(InputConstants.Type.MOUSE.getOrCreate(event.button()));
        return true;
    }

    private void bind(InputConstants.Key key) {
        this.keyMapping.setKey(key);
        KeyMapping.resetMapping();
        this.minecraft.options.save();
        this.minecraft.setScreen(afterBinding.get());
    }
}
