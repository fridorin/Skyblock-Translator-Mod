package ru.fridorin.translator;

import static org.junit.Assert.*;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.junit.Test;

public class KeyBindingTest {
    @Test
    public void toggleUsesThePlatformsUnboundKeyAndRepairsOldSavedValues() {
        KeyMapping toggle = TranslatorModClient.createToggleKey(KeyMapping.Category.MISC);
        assertTrue(toggle.isUnbound());

        toggle.setKey(InputConstants.getKey("key.keyboard.-1"));
        boolean oldInvalidValue = !toggle.isUnbound();
        assertEquals(oldInvalidValue, TranslatorModClient.repairLegacyToggleKey(toggle));
        assertTrue(toggle.isUnbound());

        toggle.setKey(InputConstants.getKey("key.keyboard.k"));
        assertFalse(TranslatorModClient.repairLegacyToggleKey(toggle));
        assertFalse(toggle.isUnbound());
    }
}
