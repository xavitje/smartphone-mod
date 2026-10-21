package nl.smartphonemod.client.cctv;

import nl.smartphonemod.SmartphoneMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Clips on your own computer: .minecraft/cctv/clips/*.cctv (data) and .minecraft/cctv/videos/*.mp4. */
public final class CctvClips {
    private CctvClips() {}

    private static final int MAGIC = 0x53504356; // "SPCV"

    public record Clip(Path file, String camera, String dimension, BlockPos pos, long wallMs, long durationMs) {}

    public static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("cctv");
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_\\-]+", "_");
    }

    public static Path save(String camera, String dimension, CctvReplay.Recording r) throws IOException {
        Path dir = dir().resolve("clips");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path file = dir.resolve(safe(camera) + "_" + stamp + ".cctv");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(MAGIC);
            out.writeInt(1);
            out.writeUTF(camera);
            out.writeUTF(dimension);
            out.writeLong(r.camera().asLong());
            out.writeLong(r.startWallMs());
            out.writeLong(r.durationMs());
            out.writeInt(r.raw().length);
            out.write(r.raw());
        }
        SmartphoneMod.LOGGER.info("CCTV clip saved to {}", file);
        return file;
    }

    private static Clip readHeader(Path file) {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != MAGIC || in.readInt() != 1) return null;
            return new Clip(file, in.readUTF(), in.readUTF(), BlockPos.of(in.readLong()), in.readLong(), in.readLong());
        } catch (IOException e) {
            return null;
        }
    }

    public static byte[] readData(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            in.readInt();
            in.readInt();
            in.readUTF();
            in.readUTF();
            in.readLong();
            in.readLong();
            in.readLong();
            byte[] data = new byte[in.readInt()];
            in.readFully(data);
            return data;
        }
    }

    public static List<Clip> list() {
        Path dir = dir().resolve("clips");
        List<Clip> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".cctv")).forEach(p -> {
                Clip c = readHeader(p);
                if (c != null) out.add(c);
            });
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparingLong(Clip::wallMs).reversed());
        return out;
    }

    public static void delete(Clip c) {
        try {
            Files.deleteIfExists(c.file());
        } catch (IOException ignored) {
        }
    }
}
