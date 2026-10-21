package nl.smartphonemod.client.cctv;

import com.mojang.blaze3d.platform.NativeImage;
import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.client.hud.Notifications;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Rational;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns a replay into an .mp4: the replay is played once from the start while every game tick (20 fps) the
 * picture is captured; frames are encoded with JCodec on a background thread.
 */
public final class CctvMp4 {
    private CctvMp4() {}

    private static SequenceEncoder encoder;
    private static ExecutorService worker;
    private static Path file;
    private static int frames, totalFrames;
    private static volatile boolean failed;
    private static int width, height;

    public static boolean recording() {
        return encoder != null;
    }

    public static float progress() {
        return totalFrames == 0 ? 0 : Math.min(1f, frames / (float) totalFrames);
    }

    public static void start(String camera) {
        if (encoder != null || !CctvReplay.active()) return;
        try {
            Path dir = CctvClips.dir().resolve("videos");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            file = dir.resolve(camera.replaceAll("[^A-Za-z0-9_\\-]+", "_") + "_" + stamp + ".mp4");
            encoder = SequenceEncoder.createWithFps(NIOUtils.writableChannel(file.toFile()), Rational.R(20, 1));
        } catch (Exception e) {
            SmartphoneMod.LOGGER.error("Could not start MP4 export", e);
            encoder = null;
            return;
        }
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Smartphone-MP4");
            t.setDaemon(true);
            return t;
        });
        frames = 0;
        failed = false;
        width = height = 0;
        totalFrames = CctvReplay.recording().lastTick() + 1;
        CctvReplay.seek(0);
        CctvReplay.speed(1f);
        CctvReplay.playing(true);
    }

    /** Called every client tick after the replay advanced. */
    public static void tick(Minecraft mc) {
        if (encoder == null) return;
        if (!CctvReplay.active()) {
            finish(mc);
            return;
        }
        var target = mc.gameRenderer.mainRenderTarget();
        int down = 1;
        while (target.width / down > 960 && target.width % (down + 1) == 0 && target.height % (down + 1) == 0) down++;
        if (target.width % down != 0 || target.height % down != 0) down = 1;
        Screenshot.takeScreenshot(target, down, image -> {
            SequenceEncoder enc = encoder;
            if (enc == null) {
                image.close();
                return;
            }
            Picture pic = toPicture(image);
            image.close();
            worker.execute(() -> {
                if (failed) return;
                try {
                    enc.encodeNativeFrame(pic);
                } catch (Exception e) {
                    failed = true;
                    SmartphoneMod.LOGGER.error("MP4 frame failed", e);
                }
            });
        });
        frames++;
        if (!CctvReplay.playing() || frames >= totalFrames) finish(mc);
    }

    private static Picture toPicture(NativeImage image) {
        if (width == 0) {
            width = image.getWidth() & ~1;
            height = image.getHeight() & ~1;
        }
        int w = Math.min(width, image.getWidth() & ~1), h = Math.min(height, image.getHeight() & ~1);
        Picture pic = Picture.create(width, height, ColorSpace.RGB);
        byte[] data = pic.getPlaneData(0);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getPixel(x, y);
                int o = (y * width + x) * 3;
                data[o] = (byte) (((argb >> 16) & 0xFF) - 128);
                data[o + 1] = (byte) (((argb >> 8) & 0xFF) - 128);
                data[o + 2] = (byte) ((argb & 0xFF) - 128);
            }
        }
        return pic;
    }

    private static void finish(Minecraft mc) {
        SequenceEncoder enc = encoder;
        encoder = null;
        if (enc == null) return;
        Path out = file;
        ExecutorService w = worker;
        w.execute(() -> {
            try {
                enc.finish();
                SmartphoneMod.LOGGER.info("CCTV video saved to {}", out);
                mc.execute(() -> Notifications.post("cctv", Component.translatable("smartphone.app.cctv"),
                        Component.translatable("smartphone.cctv.mp4_saved", out.getFileName().toString()), false));
            } catch (Exception e) {
                SmartphoneMod.LOGGER.error("MP4 export failed", e);
            }
        });
        w.shutdown();
    }
}
