package nl.smartphonemod.client;

import com.mojang.blaze3d.platform.InputConstants;
import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.network.C2S;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** The phone hotkey (default P, changeable in Controls). Pressing it again inside the phone closes it. */
public final class PhoneKeys {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(SmartphoneMod.id("smartphone"));
    public static final KeyMapping OPEN = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.smartphone.open", InputConstants.KEY_P, CATEGORY));

    private PhoneKeys() {}

    public static void register() {}

    public static void tick(Minecraft mc) {
        while (OPEN.consumeClick()) {
            if (CameraMode.active()) {
                CameraMode.exitToPhone();
                continue;
            }
            // The server decides: it only opens the phone when there is one in your inventory.
            if (mc.gui.screen() == null && mc.player != null && ClientPlayNetworking.canSend(C2S.OpenRequest.ID)) {
                ClientPlayNetworking.send(C2S.OpenRequest.INSTANCE);
            }
        }
    }
}
