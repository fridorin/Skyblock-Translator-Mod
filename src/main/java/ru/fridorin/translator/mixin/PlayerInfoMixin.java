package ru.fridorin.translator.mixin;

import ru.fridorin.translator.config.TranslatorConfig;
import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.service.TranslationService;
import ru.fridorin.translator.TranslatorModClient;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInfo.class)
public class PlayerInfoMixin {

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void onGetTabListDisplayName(CallbackInfoReturnable<Component> cir) {
        if (TranslatorModClient.isTranslationDisabled()) return;
        TranslatorConfig config = TranslatorConfigManager.getConfig();
        if (!config.translateTabList) return;

        Component original = cir.getReturnValue();
        if (original == null) return;
        String playerName = ((PlayerInfo) (Object) this).getProfile().name();
        Component translated = TranslatorModClient.translateComponentWithIgnore(original, playerName);
        if (translated != original) cir.setReturnValue(translated);
    }
}
