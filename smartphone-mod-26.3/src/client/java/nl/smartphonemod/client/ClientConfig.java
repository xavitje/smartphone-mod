package nl.smartphonemod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import nl.smartphonemod.SmartphoneMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Personal phone settings (Settings app), stored client-side in config/smartphone-client.json. */
public final class ClientConfig {
    public static final String[] WALLPAPERS = {"aurora", "sunset", "ocean", "forest", "graphite"};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String wallpaper = "aurora";
    public boolean ringtone = true;
    public boolean vibrate = true;
    public boolean popups = true;
    public boolean previews = true;
    public boolean use24h = true;
    /** Ringtone for messages, one of ModSounds.RINGTONES. */
    public String ringtoneName = "classic";
    /** 0 = dark, 1 = light, 2 = automatic (light by day, dark at night in the world). */
    public int theme = 0;

    private static ClientConfig instance = new ClientConfig();

    public static ClientConfig get() {
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("smartphone-client.json");
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                try (Reader r = Files.newBufferedReader(file())) {
                    ClientConfig loaded = GSON.fromJson(r, ClientConfig.class);
                    if (loaded != null) instance = loaded;
                }
            }
        } catch (Exception e) {
            SmartphoneMod.LOGGER.warn("Could not read smartphone-client.json, using defaults", e);
        }
        boolean known = false;
        for (String w : WALLPAPERS) known |= w.equals(instance.wallpaper);
        if (!known) instance.wallpaper = WALLPAPERS[0];
        if (instance.ringtoneName == null) instance.ringtoneName = "classic";
        instance.theme = Math.max(0, Math.min(2, instance.theme));
    }

    /** Whether the phone should use the light palette right now. */
    public static boolean lightNow() {
        return switch (instance.theme) {
            case 1 -> true;
            case 2 -> {
                var level = net.minecraft.client.Minecraft.getInstance().level;
                long t = level == null ? 6000 : Math.floorMod(level.getOverworldClockTime(), 24000L);
                yield t < 12500 || t > 23400; // day time in the world
            }
            default -> false;
        };
    }

    /** Applies the theme; returns true if the palette changed. */
    public static boolean updateTheme() {
        return nl.smartphonemod.client.ui.UiKit.applyTheme(lightNow());
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(instance, w);
        } catch (Exception e) {
            SmartphoneMod.LOGGER.warn("Could not save smartphone-client.json", e);
        }
    }
}
