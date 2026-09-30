plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom-remap") version "1.17-SNAPSHOT" apply false
}

// The version currently active for editing/running in the IDE.
// Switch it with `./gradlew stonecutterSwitchTo<version>`.
stonecutter active "1.21.11" /* [SC] DO NOT EDIT */

// Pure identifier/package renames between <26.1 (obfuscated, Yarn/Mojmap-era APIs) and 26.1+
// (unobfuscated, renamed APIs). These are mechanical 1:1 renames reused dozens of times each
// (e.g. across the whole /translator command tree), so a text swap avoids duplicating that logic
// with a //? if block per call site. Structural differences (changed method signatures, extra
// constructor args) are handled inline with //? if instead — see TranslatorModClient,
// TranslationService, SkyblockDictionary, TranslatorConfigScreen, ChatComponentMixin,
// KeyBindCaptureScreen.
stonecutter parameters {
    replacements {
        // Fabric API: fabric-command-api-v2 renamed its client command builder class
        string(current.parsed >= "26.1") { replace("ClientCommandManager", "ClientCommands") }
        // Fabric API: fabric-key-binding-api-v1 (client.keybinding.v1) was replaced by
        // fabric-key-mapping-api-v1 (client.keymapping.v1)
        string(current.parsed >= "26.1") { replace("net.fabricmc.fabric.api.client.keybinding.v1", "net.fabricmc.fabric.api.client.keymapping.v1") }
        string(current.parsed >= "26.1") { replace("KeyBindingHelper", "KeyMappingHelper") }
        string(current.parsed >= "26.1") { replace("registerKeyBinding", "registerKeyMapping") }
        // Minecraft: GuiMessage moved from net.minecraft.client to net.minecraft.client.multiplayer.chat
        string(current.parsed >= "26.1") { replace("net.minecraft.client.GuiMessage", "net.minecraft.client.multiplayer.chat.GuiMessage") }
        // Minecraft: GuiGraphics was renamed to GuiGraphicsExtractor, and its drawString/
        // drawCenteredString methods were renamed to text/centeredText (part of the render-state
        // Minecraft: GuiGraphics -> GuiGraphicsExtractor in 26.1
        string(current.parsed >= "26.1") { replace("GuiGraphics", "GuiGraphicsExtractor") }
        string(current.parsed >= "26.1") { replace("graphics.drawCenteredString", "graphics.centeredText") }

        // 26.2 goes further still: Minecraft.setScreen, Gui.getChat, Gui.setOverlayMessage and
        // Minecraft.getToastManager were all moved onto a new Hud object nested under Gui.
        string(current.parsed >= "26.2") { replace("client.setScreen(", "client.gui.setScreen(") }
        string(current.parsed >= "26.2") { replace("Minecraft.getInstance().setScreen(", "Minecraft.getInstance().gui.setScreen(") }
        string(current.parsed >= "26.2") { replace("minecraft.setScreen(", "minecraft.gui.setScreen(") }
        
        string(current.parsed >= "26.2") { replace("gui.getChat()", "gui.hud.getChat()") }
        string(current.parsed >= "26.2") { replace("gui.setOverlayMessage(", "gui.hud.setOverlayMessage(") }
        
        string(current.parsed >= "26.2") { replace("client.getToastManager()", "client.gui.toastManager()") }

        // 26.3 (1.21.4) migrated from GLFW to SDL3: InputConstants.Type.KEYSYM renamed to KEYBOARD
        string(current.parsed >= "26.3") { replace("InputConstants.Type.KEYSYM", "InputConstants.Type.KEYBOARD") }
    }
}
