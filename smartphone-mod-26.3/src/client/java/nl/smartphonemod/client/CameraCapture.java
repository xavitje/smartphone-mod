package nl.smartphonemod.client;

import com.mojang.blaze3d.platform.NativeImage;
import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.registry.ModSounds;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * Takes a photo for the Camera app: hides the phone and HUD for a couple of frames, grabs the
 * frame, shrinks it to at most 320 px wide, encodes it as a JPEG and uploads it.
 */
public final class CameraCapture {
    private static final int TARGET_WIDTH = 320;
    private static int countdown = -1;
    private static boolean hudWasHidden;
    /** Part of the screen to keep (fractions 0..1), and whether we stay in the walking camera afterwards. */
    private static float cx0, cy0, cx1 = 1, cy1 = 1;
    private static boolean walking;

    private CameraCapture() {}

    public static boolean busy() {
        return countdown >= 0;
    }

    /** Starts the capture: the phone closes now and reopens in Photos when the upload is done. */
    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (busy()) return;
        walking = false;
        cx0 = cy0 = 0;
        cx1 = cy1 = 1;
        hudWasHidden = mc.gui.hud.isHidden();
        if (!hudWasHidden) mc.gui.hud.toggle();
        mc.gui.setScreen(null);
        // Wait until a few frames without the phone and HUD have been rendered (more ticks on a slow machine).
        countdown = Math.max(3, Math.min(40, (int) Math.ceil(60.0 / Math.max(1, mc.getFps())) + 2));
    }

    /** Photo from the walking camera: only the viewfinder area, and the camera stays active. */
    public static void startCropped(float x0, float y0, float x1, float y1) {
        Minecraft mc = Minecraft.getInstance();
        if (busy()) return;
        walking = true;
        cx0 = x0;
        cy0 = y0;
        cx1 = x1;
        cy1 = y1;
        hudWasHidden = mc.gui.hud.isHidden();
        if (!hudWasHidden) mc.gui.hud.toggle(); // hides the viewfinder for the shot
        countdown = Math.max(2, Math.min(40, (int) Math.ceil(40.0 / Math.max(1, mc.getFps())) + 1));
    }

    /** Called every client tick. */
    public static void tick(Minecraft mc) {
        if (countdown < 0) return;
        if (--countdown > 0) return;
        countdown = -1;
        try {
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                try (image) {
                    byte[] jpeg = encode(image, cx0, cy0, cx1, cy1);
                    mc.execute(() -> finish(mc, jpeg));
                } catch (Exception e) {
                    SmartphoneMod.LOGGER.warn("Photo failed", e);
                    mc.execute(() -> finish(mc, null));
                }
            });
        } catch (Exception e) {
            SmartphoneMod.LOGGER.warn("Photo failed", e);
            finish(mc, null);
        }
    }

    private static void finish(Minecraft mc, byte[] jpeg) {
        if (!hudWasHidden && mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.SHUTTER, 1.0f, 0.8f));
        if (jpeg != null) saveLocalCopy(mc, jpeg);
        if (jpeg != null && ClientPlayNetworking.canSend(C2S.PhotoUpload.ID)) {
            ClientPlayNetworking.send(new C2S.PhotoUpload(jpeg));
        }
        if (walking) {
            if (jpeg != null) CameraMode.photoTaken();
            return; // keep filming
        }
        // Reopening is up to the server (the phone must still be in hand); the gallery opens when the upload is stored.
        PhoneScreen.pendingOpenPhotos = jpeg != null;
        if (mc.player != null && mc.gui.screen() == null) {
            mc.gui.setScreen(PhoneScreen.reopen());
        }
    }

    /** Also keeps the photo on your own computer: .minecraft/screenshots/smartphone/. */
    private static void saveLocalCopy(Minecraft mc, byte[] jpeg) {
        try {
            java.nio.file.Path dir = mc.gameDirectory.toPath().resolve("screenshots").resolve("smartphone");
            java.nio.file.Files.createDirectories(dir);
            String name = "photo-" + new java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new java.util.Date()) + ".jpg";
            java.nio.file.Files.write(dir.resolve(name), jpeg);
        } catch (Exception e) {
            SmartphoneMod.LOGGER.warn("Could not save the photo locally", e);
        }
    }

    /** Area-averaged downscale to 320 px wide, then JPEG at decreasing quality until it fits the packet limit. */
    static byte[] encode(NativeImage src, float fx0, float fy0, float fx1, float fy1) throws Exception {
        int ox = Math.max(0, (int) (src.getWidth() * fx0)), oy = Math.max(0, (int) (src.getHeight() * fy0));
        int sw = Math.max(1, Math.min(src.getWidth() - ox, (int) (src.getWidth() * (fx1 - fx0))));
        int sh = Math.max(1, Math.min(src.getHeight() - oy, (int) (src.getHeight() * (fy1 - fy0))));
        int w = Math.min(TARGET_WIDTH, sw);
        int h = Math.max(1, Math.round(sh * (w / (float) sw)));
        if (h > 320) {
            h = 320;
            w = Math.max(1, Math.round(sw * (h / (float) sh)));
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        float fx = sw / (float) w, fy = sh / (float) h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = (int) (x * fx), x1 = Math.max(x0 + 1, (int) ((x + 1) * fx));
                int y0 = (int) (y * fy), y1 = Math.max(y0 + 1, (int) ((y + 1) * fy));
                long r = 0, g = 0, b = 0, n = 0;
                for (int yy = y0; yy < y1; yy += 2)
                    for (int xx = x0; xx < x1; xx += 2) {
                        int argb = src.getPixel(ox + xx, oy + yy);
                        r += (argb >> 16) & 255;
                        g += (argb >> 8) & 255;
                        b += argb & 255;
                        n++;
                    }
                out.setRGB(x, y, (int) ((r / n) << 16 | (g / n) << 8 | (b / n)));
            }
        }
        for (float q = 0.82f; q >= 0.3f; q -= 0.12f) {
            byte[] data = jpeg(out, q);
            if (data.length <= C2S.MAX_PHOTO_BYTES) return data;
        }
        throw new IllegalStateException("Photo too large");
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
