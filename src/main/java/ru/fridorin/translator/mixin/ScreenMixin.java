package ru.fridorin.translator.mixin;

import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.TranslatorModClient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(AbstractContainerScreen.class)
public class ScreenMixin {
    @ModifyArg(
        //? if <26.1 {
        method = "renderLabels",
        //?} else
        /*method = "extractLabels",*/
        at = @At(
            value = "INVOKE",
            //? if <26.1 {
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"
            //?} else
            /*target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"*/
        ),
        index = 1
    )
    private Component translateInventoryLabel(Component original) {
        if (!TranslatorConfigManager.getConfig().translateInventoryTitles) return original;
        return TranslatorModClient.translateComponent(original);
    }
}
