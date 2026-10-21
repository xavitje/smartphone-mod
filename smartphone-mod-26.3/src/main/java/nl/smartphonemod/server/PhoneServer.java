package nl.smartphonemod.server;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.config.SmartphoneConfig;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.registry.ModItems;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Shared server-side state: the running server, its database and small helpers. */
public final class PhoneServer {
    private static MinecraftServer server;
    private static PhoneDatabase db;

    private PhoneServer() {}

    public static void start(MinecraftServer s, PhoneDatabase database) {
        server = s;
        db = database;

    }

    public static void stop() {
        if (db != null) db.close();
        db = null;
        server = null;
        GpsService.clear();
        Cooldowns.clear();
        EconomyService.clear();
    }

    public static boolean isRunning() {
        return db != null && server != null;
    }

    public static PhoneDatabase db() {
        return db;
    }

    public static MinecraftServer server() {
        return server;
    }

    static SmartphoneConfig config() {
        return SmartphoneConfig.get();
    }

    /** The phone must be on the player: in a hand or anywhere in the inventory (so the hotkey works). */
    public static boolean hasPhone(ServerPlayer player) {
        return !player.isRemoved() && !player.isSpectator() && phoneCount(player) > 0;
    }

    public static int phoneCount(ServerPlayer player) {
        var inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(ModItems.SMARTPHONE)) n++;
        return n;
    }

    public static ServerPlayer online(UUID uuid) {
        return server == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    /** The current Minecraft day number (overworld clock), used for standing orders and interest. */
    static long currentDay() {
        if (server == null) return 0;
        return server.overworld().getOverworldClockTime() / 24000L;
    }

    /**
     * Continues a database result on the server thread, for a player who is still online.
     * Database failures are logged and reported to the player instead of crashing anything.
     */
    public static <T> void then(CompletableFuture<T> future, ServerPlayer player, BiConsumer<ServerPlayer, T> action) {
        MinecraftServer s = server;
        UUID uuid = player.getUUID();
        future.whenComplete((value, error) -> {
            if (s == null) return;
            s.execute(() -> {
                ServerPlayer current = s.getPlayerList().getPlayer(uuid);
                if (error != null) {
                    SmartphoneMod.LOGGER.error("Smartphone database operation failed", error);
                    if (current != null) current.sendOverlayMessage(Component.translatable("smartphone.error.offline").withStyle(ChatFormatting.RED));
                    return;
                }
                if (current != null) action.accept(current, value);
            });
        });
    }

    /** Continues a database result on the server thread, whether or not anyone is online. */
    public static <T> void thenOnServer(CompletableFuture<T> future, Consumer<T> action) {
        MinecraftServer s = server;
        future.whenComplete((value, error) -> {
            if (s == null) return;
            if (error != null) {
                SmartphoneMod.LOGGER.error("Smartphone database operation failed", error);
                return;
            }
            s.execute(() -> action.accept(value));
        });
    }

    /** Sends a pop-up notification to a player if they are online. */
    public static void notify(UUID uuid, String app, Component title, Component body) {
        ServerPlayer player = online(uuid);
        if (player != null) ServerPlayNetworking.send(player, new S2C.Notify(app, title, body));
    }

    static void sendUnread(ServerPlayer player, boolean onLogin) {
        then(db.unreadCount(player.getUUID()), player, (p, unread) -> {
            if (!onLogin || unread > 0) ServerPlayNetworking.send(p, new S2C.UnreadCount(unread, onLogin));
        });
    }

    // ---------------------------------------------------------------- lifecycle hooks

    public static void onJoin(ServerPlayer player) {
        if (!isRunning()) return;
        var cfg = config();
        ServerPlayNetworking.send(player, new S2C.ServerInfo(cfg.transferFeePercent, cfg.transferFeeMinimum,
                cfg.marketFeePercent, cfg.dailyInterestPercent, !"admins".equalsIgnoreCase(cfg.emergencyTarget), cfg.maxMessageLength));
        then(db.upsertAccount(player.getUUID(), player.getName().getString(), cfg.startingBalance, cfg.gpsVisibleByDefault),
                player, (p, account) -> {
                    GpsService.loadPlayer(p, account.gpsMode());
                    // Offline messages, requests and market deliveries wait in the database.
                    sendUnread(p, true);
                    BankService.announceOpenRequests(p);
                    MarketService.deliverPending(p);
                });
    }

    public static void onLeave(ServerPlayer player) {
        GpsService.unloadPlayer(player);
        Cooldowns.forget(player.getUUID());
    }

    /** Called from the item's use(): the server has just confirmed the phone is in hand. */
    public static void openPhone(ServerPlayer player) {
        if (!isRunning() || !hasPhone(player)) return;
        UUID uuid = player.getUUID();
        then(db.balance(uuid).thenCombine(db.unreadCount(uuid), (balance, unread) -> new long[]{balance, unread}),
                player, (p, values) -> ServerPlayNetworking.send(p,
                        new S2C.OpenPhone(values[0], (int) values[1], GpsService.mode(uuid))));
    }
}
