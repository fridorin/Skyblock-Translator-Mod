package ru.fridorin.translator.mixin;

import ru.fridorin.translator.config.TranslatorConfig;
import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.service.TranslationService;
import ru.fridorin.translator.TranslatorModClient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void onGetDisplayName(CallbackInfoReturnable<Component> cir) {
        Entity entity = (Entity) (Object) this;

        Component original = cir.getReturnValue();
        if (original == null) return;
        String plainText = original.getString();
        if (plainText == null || plainText.trim().isEmpty()) return;

        if (TranslatorModClient.isTranslationDisabled() || TranslationService.isBypassed()
                || entity instanceof net.minecraft.world.entity.player.Player) return;
        TranslatorConfig config = TranslatorConfigManager.getConfig();
        if (config.ignoreDamageIndicators && TranslationService.isDamageIndicator(plainText)) return;

        boolean isMob = false;
        boolean isNpc = false;
        boolean isHologram = false;

        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand) {
            net.minecraft.world.entity.decoration.ArmorStand armorStand = (net.minecraft.world.entity.decoration.ArmorStand) entity;
            boolean isHologramBase = armorStand.isInvisible() && armorStand.isNoGravity();
            
            if ((plainText.contains("❤") && !isHologramBase) || plainText.contains("HP") || plainText.contains("[Lv") || plainText.contains("Lv ")) {
                isMob = true;
            } else if (plainText.contains("[NPC]") || ru.fridorin.translator.service.SkyblockDictionary.isNpcName(plainText)) {
                isNpc = true;
            } else if (isHologramBase) {
                isHologram = true;
            } else {
                isMob = true;
            }
        } else {
            if (entity instanceof net.minecraft.world.entity.monster.Monster || 
                entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon || 
                entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
                isMob = true;
            } else if (entity instanceof net.minecraft.world.entity.npc.villager.Villager) {
                isNpc = true;
            } else {
                isMob = true;
            }
        }

        if ((isMob && !config.translateMobNames) || (isNpc && !config.translateNpcNames)
                || (isHologram && !config.translateHolograms)) return;
        Component translated = TranslatorModClient.translateComponent(original);
        if (translated != original) cir.setReturnValue(translated);
    }
}
