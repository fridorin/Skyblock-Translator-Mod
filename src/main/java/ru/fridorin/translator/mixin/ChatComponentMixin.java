package ru.fridorin.translator.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import java.util.List;
import ru.fridorin.translator.ChatUpdateExtension;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements ChatUpdateExtension {
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow abstract void refreshTrimmedMessages();

    @Override
    public void skyblock_translator$updateMessage(Component original, Component translation) {
        for (int i = 0; i < allMessages.size(); i++) {
            GuiMessage msg = allMessages.get(i);
            // Equal text can belong to a later, different message after history is cleared.
            if (msg.content() == original) {
                //? if <26.1 {
                allMessages.set(i, new GuiMessage(msg.addedTime(), translation, msg.signature(), msg.tag()));
                //?} else
                /*allMessages.set(i, new GuiMessage(msg.addedTime(), translation, msg.signature(), msg.source(), msg.tag()));*/
                refreshTrimmedMessages();
                break;
            }
        }
    }
}
