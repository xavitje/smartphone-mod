package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.CameraMode;
import nl.smartphonemod.client.phone.PhoneApp;
import net.minecraft.client.gui.layouts.LinearLayout;

/**
 * Camera: opening the app lifts the phone up as a sideways viewfinder (see {@link CameraMode}),
 * so you can keep walking and looking around while you frame the shot.
 */
public class CameraApp extends PhoneApp {
    @Override
    public String id() {
        return "camera";
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected void build(LinearLayout col) {
        phone.afterLayout(() -> mc.execute(CameraMode::enter)); // leave the phone screen after it finished opening
    }
}
