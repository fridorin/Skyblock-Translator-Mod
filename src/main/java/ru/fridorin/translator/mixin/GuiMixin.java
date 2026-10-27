package ru.fridorin.translator.mixin;

import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.TranslatorModClient;
//? if <26.2 {
import net.minecraft.client.gui.Gui;
//?} else
/*import net.minecraft.client.gui.Hud;*/
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

//? if <26.2 {
@Mixin(Gui.class)
//?} else
/*@Mixin(Hud.class)*/
public class GuiMixin {
    @ModifyArg(
        method = "displayScoreboardSidebar",
        at = @At(
            value = "INVOKE",
            //? if <26.1 {
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"
            //?} else
            /*target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"*/
        ),
        index = 1
    )
    private Component modifyScoreboardText(Component text) {
        if (text == null || TranslatorModClient.isTranslationDisabled()
                || !TranslatorConfigManager.getConfig().translateScoreboard) return text;
        return TranslatorModClient.translateComponent(text);
    }
}
