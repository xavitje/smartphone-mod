package nl.smartphonemod.client.hud;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.client.ClientConfig;
import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.registry.ModSounds;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Corner pop-ups (like a phone's notification banners), notification sounds/vibration,
 * the unread badge and the waypoint compass, all drawn on the HUD.
 */
public final class Notifications {
    public static final Identifier HUD_ID = SmartphoneMod.id("phone_hud");
    private static final long SHOW_MS = 4500, SLIDE_MS = 250;
    private static final int WIDTH = 150;

    public record Note(String app, Component title, Component body, long time) {}

    private static final Deque<Note> QUEUE = new ArrayDeque<>();
    private static final List<Note> SHOWING = new ArrayList<>();

    private Notifications() {}

    public static Identifier icon(String app) {
        return SmartphoneMod.id("textures/gui/app/" + switch (app) {
            case "messages", "bank", "map", "market", "camera", "photos", "settings", "calculator", "clock", "emergency", "storage", "parkour", "cctv" -> app;
            default -> "settings";
        } + ".png");
    }

    /**
     * Shows a notification: a banner inside the phone if it is open, otherwise a corner pop-up.
     * Plays the ringtone or vibration according to the Settings app.
     */
    public static void post(String app, Component title, Component body, boolean urgent) {
        Minecraft mc = Minecraft.getInstance();
        ClientConfig cfg = ClientConfig.get();
        if (!cfg.previews && !urgent) body = Component.translatable("smartphone.notify.hidden_preview");
        Note note = new Note(app, title, body, 0);
        if (urgent) play(ModSounds.ALARM, 1.0f);
        else if (cfg.ringtone) play("messages".equals(app) ? ModSounds.ringtone(cfg.ringtoneName) : ModSounds.NOTIFY, 0.9f);
        else if (cfg.vibrate) play(ModSounds.VIBRATE, 0.5f);

        if (mc.gui.screen() instanceof PhoneScreen phone) {
            phone.banner(note, cfg.vibrate);
        } else if (cfg.popups || urgent) {
            QUEUE.add(note);
        }
    }

    public static void post(S2C.Notify n) {
        post(n.app(), n.title(), n.body(), "emergency".equals(n.app()));
    }

    private static void play(SoundEvent sound, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0f, volume));
    }

    // ------------------------------------------------------------------ HUD

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        ClientConfig.updateTheme();
        long now = System.currentTimeMillis();

        // Promote queued notes (max 3 on screen).
        while (SHOWING.size() < 3 && !QUEUE.isEmpty()) {
            Note n = QUEUE.poll();
            SHOWING.add(new Note(n.app(), n.title(), n.body(), now));
        }
        SHOWING.removeIf(n -> now - n.time() > SHOW_MS + SLIDE_MS);

        int y = 6;
        if (mc.gui.screen() == null) {
            for (Note n : SHOWING) y += drawNote(g, n, now, y) + 4;
            if (SHOWING.isEmpty()) drawBadge(g);
        }
        drawCompass(g);
    }

    private static int drawNote(GuiGraphicsExtractor g, Note n, long now, int y) {
        long age = now - n.time();
        float in = Mth.clamp(age / (float) SLIDE_MS, 0, 1);
        float out = Mth.clamp((SHOW_MS + SLIDE_MS - age) / (float) SLIDE_MS, 0, 1);
        float t = Math.min(in, out);
        float ease = 1 - (1 - t) * (1 - t) * (1 - t);
        var font = Minecraft.getInstance().font;
        List<FormattedCharSequence> body = font.split(n.body(), WIDTH - 32);
        if (body.size() > 2) body = body.subList(0, 2);
        int h = 22 + body.size() * 9;
        int x = g.guiWidth() - 6 - (int) (WIDTH * ease) - (int) (6 * (1 - ease));
        UiKit.roundRect(g, x, y, WIDTH, h, 8, UiKit.withAlpha(UiKit.SURFACE, 0xE6));
        g.blit(RenderPipelines.GUI_TEXTURED, icon(n.app()), x + 6, y + 6, 0, 0, 16, 16, 64, 64, 64, 64);
        g.text(font, UiKit.ellipsize(n.title().getString(), WIDTH - 34), x + 27, y + 6, UiKit.TEXT, false);
        int ty = y + 16;
        for (FormattedCharSequence line : body) {
            g.text(font, line, x + 27, ty, UiKit.TEXT_2, false);
            ty += 9;
        }
        return h;
    }

    private static void drawBadge(GuiGraphicsExtractor g) {
        int unread = ClientPhoneState.unread;
        if (unread <= 0) return;
        var font = Minecraft.getInstance().font;
        int size = 14, x = g.guiWidth() - size - 6, y = 6;
        g.blit(RenderPipelines.GUI_TEXTURED, icon("messages"), x, y, 0, 0, size, size, 64, 64, 64, 64);
        String count = unread > 99 ? "99+" : Integer.toString(unread);
        int w = Math.max(9, font.width(count) + 4);
        UiKit.roundRect(g, x + size - w + 4, y - 3, w, 9, 4, UiKit.RED);
        g.text(font, count, x + size - w + 4 + (w - font.width(count)) / 2, y - 2, 0xFFFFFFFF, false);
    }

    /** Small arrow + distance at the top of the screen while navigating to a waypoint. */
    private static void drawCompass(GuiGraphicsExtractor g) {
        long target = ClientPhoneState.navigationTarget;
        if (target == 0) return;
        Minecraft mc = Minecraft.getInstance();
        S2C.WaypointEntry wp = ClientPhoneState.waypoints.stream().filter(w -> w.id() == target).findFirst().orElse(null);
        if (wp == null || mc.player == null) return;
        boolean sameDim = wp.dimension().equals(mc.player.level().dimension().identifier().toString());
        double dx = wp.x() + 0.5 - mc.player.getX(), dz = wp.z() + 0.5 - mc.player.getZ();
        int dist = (int) Math.sqrt(dx * dx + dz * dz);
        if (sameDim && dist < 3) { // arrived
            ClientPhoneState.navigationTarget = 0;
            post("map", Component.translatable("smartphone.map.arrived_title"), Component.literal(wp.name()), false);
            return;
        }
        int cx = g.guiWidth() / 2, y = 4;
        String label = wp.name() + "  " + (sameDim ? dist + "m" : "—");
        var font = mc.font;
        int w = font.width(label) + 26;
        UiKit.roundRect(g, cx - w / 2, y, w, 16, 8, UiKit.withAlpha(UiKit.SURFACE, 0xCC));
        if (sameDim) {
            double angle = Math.atan2(dz, dx) - Math.toRadians(mc.player.getYRot() + 90);
            drawArrow(g, cx - w / 2 + 10, y + 8, angle);
        }
        g.text(font, label, cx - w / 2 + 20, y + 4, UiKit.TEXT, false);
    }

    /** A small arrow pointing in direction {@code angle} (0 = up on screen). */
    public static void drawArrow(GuiGraphicsExtractor g, int cx, int cy, double angle) {
        double sin = Math.sin(angle), cos = Math.cos(angle);
        for (int i = -5; i <= 5; i++) {
            // shaft
            int px = (int) Math.round(cx + sin * i * 0.6), py = (int) Math.round(cy - cos * i * 0.6);
            g.fill(px, py, px + 1, py + 1, UiKit.BLUE);
        }
        for (int k = 0; k <= 3; k++) { // head
            for (int s = -k; s <= k; s++) {
                double ax = 3 - k * 0.9, ay = s * 0.7;
                int px = (int) Math.round(cx + sin * ax + cos * ay), py = (int) Math.round(cy - cos * ax + sin * ay);
                g.fill(px, py, px + 1, py + 1, UiKit.BLUE);
            }
        }
    }
}
