package nl.smartphonemod.server;

import nl.smartphonemod.SmartphoneMod;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Automatic copies of the smartphone database in {@code <world>/smartphone/backups/}.
 * A copy is made at server start when the last one is older than the interval, and then every
 * interval while the server runs. Only the newest {@code backupsToKeep} automatic copies are kept.
 */
public final class BackupService {
    private static final String PREFIX = "smartphone-";
    private static int ticks;

    private BackupService() {}

    public static void start() {
        ticks = 0;
        maybeBackup();
    }

    public static void tick(MinecraftServer server) {
        if (!PhoneServer.isRunning()) return;
        if (++ticks % (20 * 60 * 10) == 0) maybeBackup(); // check every 10 minutes
    }

    private static void maybeBackup() {
        int hours = PhoneServer.config().backupIntervalHours;
        if (hours <= 0) return;
        PhoneDatabase db = PhoneServer.db();
        db.submit(c -> Long.parseLong(PhoneDatabase.meta(c, "last_backup", "0"))).thenAccept(last -> {
            if (System.currentTimeMillis() - last >= hours * 3_600_000L) backup();
        });
    }

    /** Makes a backup now. Completes with the file that was written. */
    public static CompletableFuture<Path> backup() {
        PhoneDatabase db = PhoneServer.db();
        Path dir = db.backupDir();
        Path file = dir.resolve(PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".db");
        return db.submit(c -> {
            try {
                Files.createDirectories(dir);
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new java.sql.SQLException(e);
            }
            PhoneDatabase.vacuumInto(c, file);
            PhoneDatabase.setMeta(c, "last_backup", Long.toString(System.currentTimeMillis()));
            prune(dir, PhoneServer.config().backupsToKeep);
            SmartphoneMod.LOGGER.info("Smartphone database backed up to {}", file);
            return file;
        }).whenComplete((f, e) -> {
            if (e != null) SmartphoneMod.LOGGER.error("Smartphone database backup failed", e);
        });
    }

    private static void prune(Path dir, int keep) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> backups = files.filter(p -> p.getFileName().toString().startsWith(PREFIX)
                    && p.getFileName().toString().endsWith(".db")).sorted().toList();
            for (int i = 0; i < backups.size() - keep; i++) Files.deleteIfExists(backups.get(i));
        } catch (IOException e) {
            SmartphoneMod.LOGGER.warn("Could not clean up old smartphone backups", e);
        }
    }
}
