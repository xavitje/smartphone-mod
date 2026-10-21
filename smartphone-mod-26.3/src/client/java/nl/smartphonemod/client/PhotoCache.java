package nl.smartphonemod.client;

import com.mojang.blaze3d.platform.NativeImage;
import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.network.C2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Downloads photos on demand and keeps a small cache of them as GPU textures. */
public final class PhotoCache {
    public record Photo(Identifier texture, int width, int height) {}

    private static final int MAX = 48;
    private static final LinkedHashMap<Long, Photo> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static final Set<Long> REQUESTED = new HashSet<>();
    private static final Map<Long, Long> REQUEST_TIME = new HashMap<>();

    private PhotoCache() {}

    /** Returns the photo if loaded; otherwise asks the server for it (at most every few seconds) and returns null. */
    public static Photo get(long id) {
        if (id <= 0) return null;
        Photo p = CACHE.get(id);
        if (p != null) return p;
        long now = System.currentTimeMillis();
        if (!REQUESTED.contains(id) || now - REQUEST_TIME.getOrDefault(id, 0L) > 5000) {
            REQUESTED.add(id);
            REQUEST_TIME.put(id, now);
            if (ClientPlayNetworking.canSend(C2S.PhotoRequest.ID)) ClientPlayNetworking.send(new C2S.PhotoRequest(id));
        }
        return null;
    }

    /** Called when the server sends a photo. Decodes the JPEG into a texture. */
    public static void receive(long id, byte[] jpeg) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) return;
            int w = img.getWidth(), h = img.getHeight();
            NativeImage ni = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    ni.setPixel(x, y, 0xFF000000 | img.getRGB(x, y));
            Identifier tex = SmartphoneMod.id("dynamic/photo_" + id);
            Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(() -> "Phone photo " + id, ni));
            CACHE.put(id, new Photo(tex, w, h));
            REQUESTED.remove(id);
            while (CACHE.size() > MAX) {
                var eldest = CACHE.entrySet().iterator().next();
                Minecraft.getInstance().getTextureManager().release(eldest.getValue().texture());
                CACHE.remove(eldest.getKey());
            }
        } catch (Exception e) {
            SmartphoneMod.LOGGER.warn("Could not decode photo {}", id, e);
        }
    }

    public static void clear() {
        for (Photo p : CACHE.values()) Minecraft.getInstance().getTextureManager().release(p.texture());
        CACHE.clear();
        REQUESTED.clear();
        REQUEST_TIME.clear();
    }
}
