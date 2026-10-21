package nl.smartphonemod.client;

import nl.smartphonemod.client.hud.Notifications;
import nl.smartphonemod.client.screen.AtmScreen;
import nl.smartphonemod.registry.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

public final class SmartphoneClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        ClientNetworking.register();
        PhoneKeys.register();
        MenuScreens.register(ModMenus.ATM, AtmScreen::new);
        MenuScreens.register(ModMenus.STORAGE, nl.smartphonemod.client.screen.StorageScreen::new);
        net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(nl.smartphonemod.registry.ModBlockEntities.CCTV_CAMERA,
                nl.smartphonemod.client.cctv.CctvCameraRenderer::new);
        HudElementRegistry.addLast(CameraMode.HUD_ID, CameraMode::render);
        HudElementRegistry.addLast(Notifications.HUD_ID, Notifications::render);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            CameraCapture.tick(mc);
            CameraMode.tick(mc);
            PhoneKeys.tick(mc);
            ClockState.tick();
            nl.smartphonemod.client.cctv.CctvView.tick(mc);
            nl.smartphonemod.client.cctv.CctvReplay.tick();
            nl.smartphonemod.client.cctv.CctvMp4.tick(mc);
        });
    }
}
