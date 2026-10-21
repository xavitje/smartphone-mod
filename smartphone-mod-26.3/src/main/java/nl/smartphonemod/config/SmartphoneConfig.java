package nl.smartphonemod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import nl.smartphonemod.SmartphoneMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side settings, stored in config/smartphone.json.
 * Only the server's copy matters: clients receive whatever they need through packets.
 */
public final class SmartphoneConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Physical items the ATM accepts, and how many balance units ("diamonds") each one is worth. */
    public Map<String, Long> currency = new LinkedHashMap<>(Map.of(
            "minecraft:diamond", 1L,
            "minecraft:diamond_block", 9L
    ));

    /** Balance every new account starts with. */
    public long startingBalance = 0;

    /** Largest single transfer allowed through the Bank app. */
    public long maxTransfer = 1_000_000;

    /** Maximum characters in one direct message. */
    public int maxMessageLength = 256;

    /** Minimum milliseconds between two messages from the same player (spam protection). */
    public long messageCooldownMs = 750;

    /** Minimum milliseconds between two transfers from the same player. */
    public long transferCooldownMs = 1000;

    /** Horizontal radius (blocks) in which other players appear on the GPS map. */
    public int gpsRadius = 256;

    /** How often (ticks) GPS positions are broadcast to players who have the Map app open. */
    public int gpsIntervalTicks = 10;

    /** Whether players are visible on other people's maps until they change it themselves. */
    public boolean gpsVisibleByDefault = true;

    // ---------------------------------------------------------------- economy

    /** Fee on transfers, payment requests and standing orders, as a percentage of the amount (0 = free). */
    public double transferFeePercent = 0.0;

    /** Minimum fee per transfer when transferFeePercent is above 0. */
    public long transferFeeMinimum = 0;

    /** Daily interest on balances, as a percentage (0 = none). Paid once per Minecraft day. */
    public double dailyInterestPercent = 0.0;

    /** Interest is only paid over this part of a balance, so rich players don't snowball. */
    public long interestMaxBalance = 1_000;

    /** Fee the Marktplaats keeps from each sale, as a percentage of the price. */
    public double marketFeePercent = 0.0;

    /** Maximum number of active listings per player on the Marktplaats. */
    public int maxListingsPerPlayer = 10;

    /** Largest price allowed for one listing. */
    public long maxListingPrice = 1_000_000;

    // ---------------------------------------------------------------- social

    /** Maximum members in a group chat (including the creator). */
    public int maxGroupMembers = 20;

    /** Maximum waypoints per player. */
    public int maxWaypoints = 30;

    /** Maximum photos stored per player; the oldest is removed when a new one is taken. */
    public int maxPhotosPerPlayer = 40;

    /** Who receives an emergency (112) alert: "all" online players, or only "admins". */
    public String emergencyTarget = "all";

    /** Seconds a player must wait between two emergency alerts. */
    public int emergencyCooldownSeconds = 60;

    // ---------------------------------------------------------------- admin

    /** Hours between automatic database backups (0 = off). */
    public int backupIntervalHours = 24;

    /** How many automatic backups to keep in <world>/smartphone/backups. */
    public int backupsToKeep = 7;

    /** Transfers at or above this amount show up in /phone suspicious. */
    public long suspiciousAmount = 1_000;

    /** More outgoing transfers than this within one hour also show up in /phone suspicious. */
    public int suspiciousTransfersPerHour = 20;

    // ---------------------------------------------------------------- storage network

    /** Most containers one storage hub can link. */
    public int storageMaxLinks = 4096;

    /** Linked containers must be within this many blocks of the hub (same dimension). */
    public int storageMaxRange = 256;

    /** Largest side of a box selected with the linker (sneak + right-click two corners). */
    public int storageMaxAreaSize = 128;

    /** Whether the phone's Storage app may open hubs from anywhere (owner and the owner's friends only). */
    public boolean storageRemoteAccess = true;

    /** Balance charged each time a hub is opened through the phone (0 = free). */
    public long storageRemoteFee = 0;

    /** If true, only the owner, the owner's friends and admins can use a hub, also at the block itself. */
    public boolean storageHubPrivate = false;

    // ---------------------------------------------------------------- parkour

    /** Racers must be within this many blocks of the start when a race begins (they are then put on the start). */
    public int parkourRaceRadius = 32;

    /** Small particles on start (green), checkpoints (sparks) and finish (fireworks) when players are near. */
    public boolean parkourMarkerParticles = true;

    // ---------------------------------------------------------------- cctv

    /** Every security camera keeps its own chunk loaded and ticking (like /forceload), so it always has a picture. */
    public boolean cctvKeepLoaded = true;

    /** View distance (chunks) while watching a camera. */
    public int cctvViewDistance = 4;

    /** Radius (blocks) around a camera that is remembered for the last 30 seconds (in memory only). */
    public int cctvRecordRadius = 24;

    private static double clampPct(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(v, 100)) : 0;
    }

    /** Fee for moving {@code amount}, following transferFeePercent / transferFeeMinimum. */
    public long transferFee(long amount) {
        if (transferFeePercent <= 0) return 0;
        return Math.max(transferFeeMinimum, (long) Math.floor(amount * transferFeePercent / 100.0));
    }

    public long marketFee(long price) {
        return marketFeePercent <= 0 ? 0 : (long) Math.floor(price * marketFeePercent / 100.0);
    }

    private static SmartphoneConfig instance = new SmartphoneConfig();

    public static SmartphoneConfig get() {
        return instance;
    }

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("smartphone.json");
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    SmartphoneConfig loaded = GSON.fromJson(reader, SmartphoneConfig.class);
                    if (loaded != null) instance = loaded;
                }
            }
            instance.sanitize();
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException | RuntimeException e) {
            SmartphoneMod.LOGGER.error("Could not read config/smartphone.json, using defaults", e);
            instance = new SmartphoneConfig();
        }
    }

    private void sanitize() {
        if (currency == null || currency.isEmpty()) currency = new SmartphoneConfig().currency;
        currency.values().removeIf(v -> v == null || v <= 0);
        maxMessageLength = Math.max(1, Math.min(maxMessageLength, 1024));
        gpsIntervalTicks = Math.max(2, gpsIntervalTicks);
        gpsRadius = Math.max(16, gpsRadius);
        maxTransfer = Math.max(1, maxTransfer);
        startingBalance = Math.max(0, startingBalance);
        transferFeePercent = clampPct(transferFeePercent);
        marketFeePercent = clampPct(marketFeePercent);
        dailyInterestPercent = clampPct(dailyInterestPercent);
        transferFeeMinimum = Math.max(0, transferFeeMinimum);
        interestMaxBalance = Math.max(0, interestMaxBalance);
        maxListingsPerPlayer = Math.max(1, maxListingsPerPlayer);
        maxListingPrice = Math.max(1, maxListingPrice);
        maxGroupMembers = Math.max(2, Math.min(maxGroupMembers, 100));
        maxWaypoints = Math.max(1, Math.min(maxWaypoints, 200));
        maxPhotosPerPlayer = Math.max(1, Math.min(maxPhotosPerPlayer, 500));
        emergencyCooldownSeconds = Math.max(5, emergencyCooldownSeconds);
        backupIntervalHours = Math.max(0, backupIntervalHours);
        backupsToKeep = Math.max(1, Math.min(backupsToKeep, 365));
        if (!"admins".equalsIgnoreCase(emergencyTarget)) emergencyTarget = "all";
        suspiciousAmount = Math.max(1, suspiciousAmount);
        suspiciousTransfersPerHour = Math.max(1, suspiciousTransfersPerHour);
        storageMaxLinks = Math.max(1, Math.min(storageMaxLinks, 32768));
        storageMaxRange = Math.max(8, Math.min(storageMaxRange, 1024));
        storageMaxAreaSize = Math.max(4, Math.min(storageMaxAreaSize, 512));
        storageRemoteFee = Math.max(0, storageRemoteFee);
        parkourRaceRadius = Math.max(4, Math.min(parkourRaceRadius, 256));
        cctvViewDistance = Math.max(2, Math.min(cctvViewDistance, 8));
        cctvRecordRadius = Math.max(4, Math.min(cctvRecordRadius, 64));
    }
}
