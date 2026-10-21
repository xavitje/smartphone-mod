package nl.smartphonemod.client.cctv;

import com.mojang.blaze3d.platform.InputConstants;
import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** The CCTV monitor: the world is rendered from the camera behind this overlay. Live or replay with controls. */
public class CctvScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy  HH:mm:ss");
    private static final int M = 14;

    private record Btn(int x, int y, int w, int h, Component label, Runnable action, boolean on) {
        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Btn> buttons = new ArrayList<>();
    private boolean draggingTimeline;

    public CctvScreen() {
        super(Component.translatable("smartphone.app.cctv"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        // No blur or darkening: the camera picture is the background.
    }

    private List<S2C.CctvCamera> switchable() {
        List<S2C.CctvCamera> out = new ArrayList<>();
        if (ClientPhoneState.cctvCameras != null) {
            for (S2C.CctvCamera c : ClientPhoneState.cctvCameras) if (c.sameDim() && c.online()) out.add(c);
        }
        return out;
    }

    private int timelineY() {
        return height - M - 44;
    }

    private int timelineX0() {
        return M + 8;
    }

    private int timelineX1() {
        return width - M - 8;
    }

    private void layoutButtons() {
        buttons.clear();
        int y = height - M - 32, x = M + 8;
        if (CctvReplay.active()) {
            x = add(x, y, Component.literal(CctvReplay.playing() ? "II" : "▶"), () -> CctvReplay.playing(!CctvReplay.playing()), false);
            for (float s : new float[]{0.5f, 1f, 2f}) {
                x = add(x, y, Component.literal((s == 0.5f ? "½" : Integer.toString((int) s)) + "×"), () -> CctvReplay.speed(s), CctvReplay.speed() == s);
            }
            x = add(x + 6, y, Component.translatable("smartphone.cctv.btn.live").withStyle(ChatFormatting.RED), CctvReplay::stop, false);
            x = add(x + 6, y, Component.translatable("smartphone.cctv.btn.clip"), CctvView::requestClip, false);
            add(x, y, Component.translatable("smartphone.cctv.btn.mp4"), () -> CctvMp4.start(CctvView.name()), false);
        } else {
            x = add(x, y, Component.translatable("smartphone.cctv.btn.rewind"), CctvView::requestReplay, false);
            add(x, y, Component.translatable("smartphone.cctv.btn.clip"), CctvView::requestClip, false);
        }
    }

    private int add(int x, int y, Component label, Runnable action, boolean on) {
        int w = font.width(label) + 12;
        buttons.add(new Btn(x, y, w, 14, label, action, on));
        return x + w + 4;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        if (!CctvView.active()) return;
        int w = width, h = height;
        long now = System.currentTimeMillis();
        boolean replay = CctvReplay.active();
        boolean exporting = CctvMp4.recording();
        // Vignette + scanlines for the security-monitor look.
        for (int i = 0; i < 24; i++) {
            int alpha = (int) (110 * (1 - i / 24f) * (1 - i / 24f));
            int c = alpha << 24;
            g.fill(i, i, w - i, i + 1, c);
            g.fill(i, h - i - 1, w - i, h - i, c);
            g.fill(i, i + 1, i + 1, h - i - 1, c);
            g.fill(w - i - 1, i + 1, w - i, h - i - 1, c);
        }
        int scan = (int) ((now / 25) % (h + 40)) - 20;
        g.fill(0, scan, w, scan + 14, 0x0CFFFFFF);
        for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, 0x10000000);
        int l = 18, c = 0xCCFFFFFF;
        g.fill(M, M, M + l, M + 1, c); g.fill(M, M, M + 1, M + l, c);
        g.fill(w - M - l, M, w - M, M + 1, c); g.fill(w - M - 1, M, w - M, M + l, c);
        g.fill(M, h - M - 1, M + l, h - M, c); g.fill(M, h - M - l, M + 1, h - M, c);
        g.fill(w - M - l, h - M - 1, w - M, h - M, c); g.fill(w - M - 1, h - M - l, w - M, h - M, c);

        boolean blink = (now / 600) % 2 == 0;
        if (replay) {
            g.text(font, Component.translatable("smartphone.cctv.replay").withStyle(ChatFormatting.BOLD), M + 8, M + 9, 0xFFFFD050, true);
        } else {
            if (blink) g.fill(M + 8, M + 10, M + 14, M + 16, 0xFFFF3030);
            g.text(font, Component.literal("LIVE").withStyle(ChatFormatting.BOLD), M + 18, M + 9, 0xFFFF5050, true);
        }
        g.text(font, Component.literal(CctvView.name()), M + 8, M + 22, 0xFFFFFFFF, true);
        var p = CctvView.pos();
        g.text(font, Component.literal(p.getX() + ", " + p.getY() + ", " + p.getZ()), M + 8, M + 33, 0xFFB0B0B0, true);
        long shown = replay ? CctvReplay.shownWallMs() : now;
        String t = LocalDateTime.ofInstant(Instant.ofEpochMilli(shown), ZoneId.systemDefault()).format(TIME);
        g.text(font, Component.literal(t), w - M - 8 - font.width(t), M + 9, 0xFFFFFFFF, true);
        List<S2C.CctvCamera> cams = switchable();
        int idx = indexOf(cams);
        if (idx >= 0) {
            String n = "CAM " + (idx + 1) + "/" + cams.size();
            g.text(font, Component.literal(n), w - M - 8 - font.width(n), M + 21, 0xFFB0B0B0, true);
        }
        if (CctvView.zoom() > 1.01f) {
            String z = String.format(java.util.Locale.ROOT, "%.1fx", CctvView.zoom());
            g.text(font, Component.literal(z), w - M - 8 - font.width(z), M + 33, 0xFFFFD050, true);
        }
        if (exporting) return; // the video gets the clean picture with time stamp only

        if (replay) {
            CctvReplay.Recording r = CctvReplay.recording();
            int x0 = timelineX0(), x1 = timelineX1(), ty = timelineY();
            g.fill(x0, ty, x1, ty + 4, 0x80FFFFFF);
            float frac = r.lastTick() == 0 ? 1 : CctvReplay.playhead() / r.lastTick();
            int px = x0 + Math.round((x1 - x0) * frac);
            g.fill(x0, ty, px, ty + 4, 0xFFFFD050);
            g.fill(px - 1, ty - 3, px + 2, ty + 7, 0xFFFFFFFF);
            String ago = String.format(java.util.Locale.ROOT, "-%.1fs", (r.lastTick() - CctvReplay.playhead()) / 20f);
            g.text(font, Component.literal(ago), x1 - font.width(ago), ty - 11, 0xFFFFFFFF, true);
        }
        layoutButtons();
        for (Btn b : buttons) {
            boolean hover = b.hit(mouseX, mouseY);
            g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), b.on() ? 0xC0FFD050 : hover ? 0xC0404040 : 0xA0000000);
            g.text(font, b.label(), b.x() + 6, b.y() + 3, b.on() ? 0xFF000000 : 0xFFFFFFFF, false);
        }
        Component hint = Component.translatable(CctvView.canControl() ? "smartphone.cctv.hint.control" : "smartphone.cctv.hint.view");
        g.text(font, hint, w - M - 8 - font.width(hint), h - M - 14, 0xFFB0B0B0, true);
    }

    private int indexOf(List<S2C.CctvCamera> cams) {
        for (int i = 0; i < cams.size(); i++) if (cams.get(i).pos().equals(CctvView.pos())) return i;
        return -1;
    }

    private void switchBy(int delta) {
        List<S2C.CctvCamera> cams = switchable();
        if (cams.size() < 2) return;
        int i = indexOf(cams);
        S2C.CctvCamera next = cams.get(Math.floorMod((i < 0 ? 0 : i) + delta, cams.size()));
        ClientPlayNetworking.send(new C2S.CctvView(next.dimension(), next.pos()));
    }

    private boolean onTimeline(double mx, double my) {
        int ty = timelineY();
        return CctvReplay.active() && mx >= timelineX0() - 3 && mx <= timelineX1() + 3 && my >= ty - 5 && my <= ty + 9;
    }

    private void seekTo(double mx) {
        CctvReplay.Recording r = CctvReplay.recording();
        if (r == null) return;
        double f = (mx - timelineX0()) / (double) (timelineX1() - timelineX0());
        CctvReplay.seek((float) (Math.max(0, Math.min(1, f)) * r.lastTick()));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (CctvMp4.recording()) return true;
        layoutButtons();
        for (Btn b : buttons) {
            if (b.hit(event.x(), event.y())) {
                b.action().run();
                return true;
            }
        }
        if (onTimeline(event.x(), event.y())) {
            draggingTimeline = true;
            seekTo(event.x());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingTimeline) {
            seekTo(event.x());
            return true;
        }
        if (CctvView.canControl() && !CctvReplay.active()) {
            float speed = 0.35f / CctvView.zoom();
            CctvView.steer((float) dx * speed, (float) dy * speed);
            CctvView.flushSteer(false);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingTimeline = false;
        CctvView.flushSteer(true);
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        CctvView.zoom(CctvView.zoom() * (scrollY > 0 ? 1.25f : 0.8f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (CctvMp4.recording()) return true;
        int key = event.key();
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_A) {
            switchBy(-1);
            return true;
        }
        if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_D) {
            switchBy(1);
            return true;
        }
        if (key == InputConstants.KEY_R) {
            CctvView.requestReplay();
            return true;
        }
        if (key == InputConstants.KEY_C) {
            CctvView.requestClip();
            return true;
        }
        if (key == InputConstants.KEY_SPACE && CctvReplay.active()) {
            CctvReplay.playing(!CctvReplay.playing());
            return true;
        }
        if (key == InputConstants.KEY_L && CctvReplay.active()) {
            CctvReplay.stop();
            return true;
        }
        if (minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (CctvReplay.active() && !CctvMp4.recording()) {
            CctvReplay.stop(); // Esc in a replay: back to live first
            return;
        }
        CctvView.end(true, true, null);
    }

    @Override
    public void removed() {
        super.removed();
        if (CctvView.active() && !CctvView.ending()) CctvView.end(true, false, null);
    }
}
