package nl.smartphonemod.client;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.phone.apps.PhotosApp;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.registry.ModItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * Walking camera: the phone is held up sideways as a viewfinder while you keep full control of the
 * player. Walk, look around and follow things; left click takes the photo, the scroll wheel zooms,
 * right click / Esc / the phone key go back to the phone.
 */
public final class CameraMode {
    public static final Identifier HUD_ID = SmartphoneMod.id("camera_viewfinder");
    private static final float MIN_ZOOM = 1f, MAX_ZOOM = 8f;

    private static boolean active;
    private static float zoom = 1f, shownZoom = 1f;
    private static long flashAt, enteredAt;
    private static int photosTaken;

    private CameraMode() {}

    public static boolean active() {
        return active;
    }

    public static void enter() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        active = true;
        zoom = shownZoom = 1f;
        photosTaken = 0;
        enteredAt = System.currentTimeMillis();
        mc.gui.setScreen(null); // back to the game: you can walk and look around
        if (ClientPlayNetworking.canSend(C2S.Gallery.ID)) ClientPlayNetworking.send(new C2S.Gallery(0));
    }

    /** Leaves the camera and goes back to the phone (to the gallery when photos were taken). */
    public static void exitToPhone() {
        Minecraft mc = Minecraft.getInstance();
        boolean showPhotos = photosTaken > 0;
        stop();
        PhoneScreen screen = PhoneScreen.reopen();
        if (showPhotos) screen.open(new PhotosApp(false));
        mc.gui.setScreen(screen);
    }

    public static void stop() {
        active = false;
        zoom = shownZoom = 1f;
    }

    public static void shutter() {
        if (!active || CameraCapture.busy() || System.currentTimeMillis() - enteredAt < 300) return;
        Minecraft mc = Minecraft.getInstance();
        float[] r = viewfinderFraction(mc);
        CameraCapture.startCropped(r[0], r[1], r[2], r[3]);
        photosTaken++;
    }

    /** Called by CameraCapture once the photo is stored. */
    static void photoTaken() {
        flashAt = System.currentTimeMillis();
    }

    public static void scroll(double amount) {
        if (amount == 0) return;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (amount > 0 ? 1.25f : 0.8f)));
    }

    /** Multiplier for the field of view (smaller = zoomed in). */
    public static float fovFactor() {
        return active ? 1f / shownZoom : 1f;
    }

    public static void tick(Minecraft mc) {
        if (!active) return;
        shownZoom += (zoom - shownZoom) * 0.35f;
        if (mc.player == null || mc.level == null) {
            stop();
            return;
        }
        if (!hasPhone(mc)) {
            stop();
            return;
        }
        var screen = mc.gui.screen();
        if (screen instanceof PauseScreen) {
            exitToPhone(); // Esc goes back to the phone instead of the pause menu
        } else if (screen != null && !CameraCapture.busy()) {
            stop(); // inventory, chat, ... : put the camera away
        }
    }

    private static boolean hasPhone(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(ModItems.SMARTPHONE)) return true;
        return false;
    }

    // ------------------------------------------------------------------ viewfinder (HUD)

    /** Landscape viewfinder rectangle in GUI coordinates: x, y, w, h. */
    private static int[] viewfinder(int sw, int sh) {
        int w = Math.min((int) (sw * 0.78f), (int) ((sh - 40) * 2.0f));
        int h = (int) (w / 2.0f);
        return new int[]{(sw - w) / 2, (sh - h) / 2 - 6, w, h};
    }

    private static float[] viewfinderFraction(Minecraft mc) {
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int[] v = viewfinder(sw, sh);
        return new float[]{v[0] / (float) sw, v[1] / (float) sh, (v[0] + v[2]) / (float) sw, (v[1] + v[3]) / (float) sh};
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return;
        int sw = g.guiWidth(), sh = g.guiHeight();
        int[] v = viewfinder(sw, sh);
        int x = v[0], y = v[1], w = v[2], h = v[3];
        int bezel = 7;
        int caseColor = caseColor(mc);

        // Dim everything outside the phone a little, like holding a phone in front of you.
        g.fill(0, 0, sw, y - bezel, 0x44000000);
        g.fill(0, y + h + bezel, sw, sh, 0x44000000);
        g.fill(0, y - bezel, x - bezel, y + h + bezel, 0x44000000);
        g.fill(x + w + bezel, y - bezel, sw, y + h + bezel, 0x44000000);

        // Phone body (sideways) as a ring around the see-through screen.
        int fx = x - bezel, fy = y - bezel, fw = w + 2 * bezel, fh = h + 2 * bezel;
        int r = 14;
        for (int row = 0; row < fh; row++) {
            int inset = 0;
            if (row < r || row >= fh - r) {
                double dy = row < r ? r - row - 0.5 : row - (fh - r) + 0.5;
                inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            }
            int ry = fy + row;
            if (ry >= y && ry < y + h) {
                g.fill(fx + inset, ry, x, ry + 1, caseColor);
                g.fill(x + w, ry, fx + fw - inset, ry + 1, caseColor);
            } else {
                g.fill(fx + inset, ry, fx + fw - inset, ry + 1, caseColor);
            }
        }
        UiKit.cornerMask(g, x, y, w, h, 9, caseColor);
        g.fill(fx - 1, fy + 30, fx, fy + 52, UiKit.shade(caseColor, 0.7f)); // side buttons
        // Dynamic island on the left (the phone is turned sideways)
        UiKit.roundRect(g, x + 3, y + h / 2 - 16, 8, 32, 4, 0xFF000000);

        // Rule-of-thirds grid and focus brackets
        for (int i = 1; i < 3; i++) {
            g.fill(x + w * i / 3, y, x + w * i / 3 + 1, y + h, 0x30FFFFFF);
            g.fill(x, y + h * i / 3, x + w, y + h * i / 3 + 1, 0x30FFFFFF);
        }
        int cx = x + w / 2, cy = y + h / 2, b = 14, l = 5;
        int bc = 0xDDFFD60A;
        g.fill(cx - b, cy - b, cx - b + l, cy - b + 1, bc);
        g.fill(cx - b, cy - b, cx - b + 1, cy - b + l, bc);
        g.fill(cx + b - l, cy - b, cx + b, cy - b + 1, bc);
        g.fill(cx + b - 1, cy - b, cx + b, cy - b + l, bc);
        g.fill(cx - b, cy + b - 1, cx - b + l, cy + b, bc);
        g.fill(cx - b, cy + b - l, cx - b + 1, cy + b, bc);
        g.fill(cx + b - l, cy + b - 1, cx + b, cy + b, bc);
        g.fill(cx + b - 1, cy + b - l, cx + b, cy + b, bc);

        // Shutter on the right edge, zoom chip, "PHOTO" label
        UiKit.circle(g, x + w - 18, cy, 11, 0xFFFFFFFF);
        UiKit.circle(g, x + w - 18, cy, 9, 0xFF000000);
        UiKit.circle(g, x + w - 18, cy, 8, 0xFFFFFFFF);
        String z = String.format(java.util.Locale.ROOT, "%.1f×", shownZoom);
        int zw = mc.font.width(z) + 8;
        UiKit.roundRect(g, cx - zw / 2, y + h - 18, zw, 12, 6, 0x88000000);
        g.text(mc.font, z, cx - zw / 2 + 4, y + h - 16, 0xFFFFD60A, false);
        Component label = Component.translatable("smartphone.camera.photo");
        g.text(mc.font, label, cx - mc.font.width(label) / 2, y + 5, 0xFFFFD60A, true);

        // Last photo thumbnail (bottom left)
        long last = ClientPhoneState.photos.isEmpty() ? 0 : ClientPhoneState.photos.get(0);
        PhotoCache.Photo p = last == 0 ? null : PhotoCache.get(last);
        if (p != null) {
            int s = 22, side = Math.min(p.width(), p.height());
            g.blit(RenderPipelines.GUI_TEXTURED, p.texture(), x + 16, y + h - s - 6, (p.width() - side) / 2f, (p.height() - side) / 2f,
                    s, s, side, side, p.width(), p.height());
        }

        // White flash after a photo
        long age = System.currentTimeMillis() - flashAt;
        if (age < 250) g.fill(x, y, x + w, y + h, ((int) (200 * (1 - age / 250f)) << 24) | 0xFFFFFF);

        // Controls hint under the phone
        Component hint = Component.translatable("smartphone.camera.hint");
        g.text(mc.font, hint, (sw - mc.font.width(hint)) / 2, fy + fh + 6, 0xFFFFFFFF, true);
    }

    private static int caseColor(Minecraft mc) {
        var inv = mc.player.getInventory();
        ItemStack stack = mc.player.getMainHandItem();
        if (!stack.is(ModItems.SMARTPHONE)) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(ModItems.SMARTPHONE)) {
                    stack = inv.getItem(i);
                    break;
                }
            }
        }
        DyedItemColor dye = stack.get(DataComponents.DYED_COLOR);
        return dye == null ? PhoneScreen.DEFAULT_CASE : 0xFF000000 | dye.rgb();
    }
}
