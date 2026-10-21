package nl.smartphonemod.client.cctv;

import nl.smartphonemod.cctv.CctvCameraBlockEntity;
import nl.smartphonemod.client.hud.Notifications;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;

/**
 * Client side of watching a security camera. The server already sends us the chunks and entities around the
 * camera; here we only move the render camera to a tiny invisible entity at the lens.
 */
public final class CctvView {
    private CctvView() {}

    private static BlockPos pos;
    private static String name = "";
    private static boolean control;
    private static Marker eye;
    private static boolean oldHideGui;
    private static float zoom = 1f;
    private static float yaw, pitch;
    private static long started;
    /** True while we end the view ourselves (so closing the screen doesn't send a second stop). */
    private static boolean ending;

    public static boolean active() {
        return pos != null;
    }

    public static BlockPos pos() {
        return pos;
    }

    public static String name() {
        return name;
    }

    public static boolean canControl() {
        return control;
    }

    public static long started() {
        return started;
    }

    public static float zoom() {
        return zoom;
    }

    public static void zoom(float z) {
        zoom = Mth.clamp(z, 1f, 6f);
    }

    public static float fovFactor() {
        return active() ? 1f / zoom : 1f;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    // ------------------------------------------------------------------ replay & clips

    private enum Pending { NONE, REPLAY, CLIP }

    private static Pending pending = Pending.NONE;
    private static java.nio.file.Path pendingClip;

    /** Ask the server for the last 30 seconds and play them. */
    public static void requestReplay() {
        if (!active()) return;
        pending = Pending.REPLAY;
        ClientPlayNetworking.send(C2S.CctvReplayRequest.INSTANCE);
    }

    /** Ask the server for the last 30 seconds and save them as a clip on this computer. */
    public static void requestClip() {
        if (!active()) return;
        if (CctvReplay.active()) {
            saveClip(CctvReplay.recording());
            return;
        }
        pending = Pending.CLIP;
        ClientPlayNetworking.send(C2S.CctvReplayRequest.INSTANCE);
    }

    /** Opens a saved clip: first watch its camera, then play the clip there. */
    public static void openClip(CctvClips.Clip clip) {
        pendingClip = clip.file();
        ClientPlayNetworking.send(new C2S.CctvView(clip.dimension(), clip.pos()));
    }

    private static void saveClip(CctvReplay.Recording r) {
        Minecraft mc = Minecraft.getInstance();
        try {
            var file = CctvClips.save(name, mc.level.dimension().identifier().toString(), r);
            Notifications.post("cctv", Component.translatable("smartphone.app.cctv"),
                    Component.translatable("smartphone.cctv.clip_saved", file.getFileName().toString()), false);
        } catch (java.io.IOException e) {
            nl.smartphonemod.SmartphoneMod.LOGGER.error("Could not save CCTV clip", e);
        }
    }

    public static void onRecording(S2C.CctvRecording p) {
        Pending what = pending;
        pending = Pending.NONE;
        if (!active() || !p.pos().equals(pos) || what == Pending.NONE) return;
        try {
            CctvReplay.Recording r = CctvReplay.decode(p.pos(), p.data());
            if (what == Pending.CLIP) saveClip(r);
            else CctvReplay.start(r);
        } catch (java.io.IOException | RuntimeException e) {
            nl.smartphonemod.SmartphoneMod.LOGGER.error("Could not read CCTV recording", e);
        }
    }

    public static void start(S2C.CctvViewStart p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        boolean was = active();
        pos = p.pos();
        name = p.name();
        control = p.canControl();
        started = System.currentTimeMillis();
        zoom = 1f;
        if (eye == null || eye.level() != mc.level) eye = new Marker(EntityTypes.MARKER, mc.level);
        if (!was) oldHideGui = mc.gui.hud.isHidden();
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        update(true);
        mc.setCameraEntity(eye);
        if (!(mc.gui.screen() instanceof CctvScreen)) mc.gui.setScreen(new CctvScreen());
        CctvReplay.stop();
        if (pendingClip != null) {
            var file = pendingClip;
            pendingClip = null;
            try {
                CctvReplay.start(CctvReplay.decode(pos, CctvClips.readData(file)));
            } catch (java.io.IOException | RuntimeException e) {
                nl.smartphonemod.SmartphoneMod.LOGGER.error("Could not open CCTV clip {}", file, e);
            }
        }
    }

    /** Ends the view on this side; {@code toServer} also tells the server, {@code backToPhone} reopens the phone. */
    public static void end(boolean toServer, boolean backToPhone, Component reason) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        CctvReplay.stop();
        pending = Pending.NONE;
        pos = null;
        ending = true;
        try {
            if (mc.player != null) mc.setCameraEntity(mc.player);
            if (mc.gui.hud.isHidden() != oldHideGui) mc.gui.hud.toggle();
            if (mc.gui.screen() instanceof CctvScreen) mc.gui.setScreen(null);
        } finally {
            ending = false;
        }
        if (toServer) ClientPlayNetworking.send(C2S.CctvStop.INSTANCE);
        if (backToPhone) ClientPlayNetworking.send(C2S.OpenRequest.INSTANCE);
        if (reason != null) Notifications.post("cctv", Component.translatable("smartphone.app.cctv"), reason, false);
    }

    static boolean ending() {
        return ending;
    }

    public static void reset() {
        CctvReplay.stop();
        pos = null;
        eye = null;
        pending = Pending.NONE;
        pendingClip = null;
    }

    /** Follows the camera block's direction (which may be steered by us or someone else). */
    public static void tick(Minecraft mc) {
        if (!active()) return;
        if (mc.level == null || mc.player == null) {
            reset();
            return;
        }
        if (mc.getCameraEntity() != eye) mc.setCameraEntity(eye);
        update(false);
    }

    private static void update(boolean snap) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof CctvCameraBlockEntity cam) {
            yaw = cam.yaw();
            pitch = cam.pitch();
        }
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        Vec3 at = Vec3.atCenterOf(pos).add(dir.scale(0.42));
        if (!snap) eye.setOldPosAndRot();
        eye.setPos(at.x, at.y, at.z);
        eye.setYRot(yaw);
        eye.setXRot(pitch);
        if (snap) eye.setOldPosAndRot();
    }

    /** Steering by the viewer (manage permission): change locally right away, tell the server. */
    public static void steer(float dYaw, float dPitch) {
        Minecraft mc = Minecraft.getInstance();
        if (!control || mc.level == null || !(mc.level.getBlockEntity(pos) instanceof CctvCameraBlockEntity cam)) return;
        cam.setViewClient(cam.yaw() + dYaw, cam.pitch() + dPitch);
    }

    private static long lastSent;
    private static float sentYaw = Float.NaN, sentPitch = Float.NaN;

    public static void flushSteer(boolean force) {
        Minecraft mc = Minecraft.getInstance();
        if (!control || mc.level == null || !(mc.level.getBlockEntity(pos) instanceof CctvCameraBlockEntity cam)) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSent < 100) return;
        if (cam.yaw() == sentYaw && cam.pitch() == sentPitch) return;
        lastSent = now;
        sentYaw = cam.yaw();
        sentPitch = cam.pitch();
        ClientPlayNetworking.send(new C2S.CctvRotate(cam.yaw(), cam.pitch()));
    }
}
