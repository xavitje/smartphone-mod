package nl.smartphonemod.server;

import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Map privacy (nobody / friends / everyone), friends, waypoints, and streaming nearby player
 * positions to players who have the Map app open. Hidden players are never sent to anyone.
 */
public final class GpsService {
    public static final int MODE_NOBODY = 0, MODE_FRIENDS = 1, MODE_EVERYONE = 2;

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    /** Privacy mode per online player (mirrored from the database so the tick never touches disk). */
    private static final Map<UUID, Integer> MODE = new ConcurrentHashMap<>();
    /** For each online player: whom they allow to see them when in friends mode. */
    private static final Map<UUID, Set<UUID>> FRIENDS = new ConcurrentHashMap<>();
    private static final int MAX_DOTS = 64;

    private GpsService() {}

    static void clear() {
        SUBSCRIBERS.clear();
        MODE.clear();
        FRIENDS.clear();
    }

    static int mode(UUID uuid) {
        return MODE.getOrDefault(uuid, PhoneServer.config().gpsVisibleByDefault ? MODE_EVERYONE : MODE_NOBODY);
    }

    static void loadPlayer(ServerPlayer player, int mode) {
        UUID me = player.getUUID();
        MODE.put(me, mode);
        PhoneServer.then(PhoneServer.db().submit(c -> friendsOf(c, me)), player, (p, set) -> FRIENDS.put(me, set));
    }

    static void unloadPlayer(ServerPlayer player) {
        SUBSCRIBERS.remove(player.getUUID());
        MODE.remove(player.getUUID());
        FRIENDS.remove(player.getUUID());
    }

    private static Set<UUID> friendsOf(java.sql.Connection c, UUID owner) throws java.sql.SQLException {
        Set<UUID> set = ConcurrentHashMap.newKeySet();
        try (PreparedStatement ps = c.prepareStatement("SELECT friend FROM friends WHERE owner = ?")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { UUID u = PhoneDatabase.uuid(rs.getString(1)); if (u != null) set.add(u); }
            }
        }
        return set;
    }

    /** May {@code viewer} see {@code target} on the map? */
    static boolean visibleTo(UUID target, UUID viewer) {
        return switch (mode(target)) {
            case MODE_EVERYONE -> true;
            case MODE_FRIENDS -> FRIENDS.getOrDefault(target, Set.of()).contains(viewer);
            default -> false;
        };
    }

    // ---------------------------------------------------------------- streaming

    public static void subscribe(ServerPlayer player) {
        SUBSCRIBERS.add(player.getUUID());
        broadcastTo(player); // immediate first frame
    }

    public static void unsubscribe(ServerPlayer player) {
        SUBSCRIBERS.remove(player.getUUID());
    }

    public static void tick(MinecraftServer server) {
        if (SUBSCRIBERS.isEmpty() || !PhoneServer.isRunning()) return;
        if (server.getTickCount() % PhoneServer.config().gpsIntervalTicks != 0) return;
        for (UUID uuid : SUBSCRIBERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || !PhoneServer.hasPhone(player)) {
                SUBSCRIBERS.remove(uuid); // phone put away: stop streaming
                continue;
            }
            broadcastTo(player);
        }
    }

    private static void broadcastTo(ServerPlayer viewer) {
        int radius = PhoneServer.config().gpsRadius;
        double maxDistSq = (double) radius * radius;
        UUID me = viewer.getUUID();
        Set<UUID> myFriends = FRIENDS.getOrDefault(me, Set.of());
        List<S2C.PlayerPos> out = new ArrayList<>();
        // Same dimension only; the viewer's own position is always included.
        for (ServerPlayer other : viewer.level().players()) {
            boolean self = other == viewer;
            if (!self) {
                if (other.isSpectator() || other.isInvisible()) continue;
                if (!visibleTo(other.getUUID(), me)) continue;
                double dx = other.getX() - viewer.getX(), dz = other.getZ() - viewer.getZ();
                if (dx * dx + dz * dz > maxDistSq) continue;
            }
            out.add(new S2C.PlayerPos(other.getUUID(), other.getName().getString(),
                    other.getBlockX(), other.getBlockZ(), other.getYRot(), myFriends.contains(other.getUUID())));
            if (out.size() >= MAX_DOTS) break;
        }
        ServerPlayNetworking.send(viewer, new S2C.Positions(out));
    }

    // ---------------------------------------------------------------- privacy & friends

    public static void setMode(ServerPlayer player, int mode) {
        if (mode < MODE_NOBODY || mode > MODE_EVERYONE) return;
        UUID me = player.getUUID();
        MODE.put(me, mode);
        PhoneServer.then(PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET gps_mode = ?, gps_visible = ? WHERE uuid = ?")) {
                ps.setInt(1, mode);
                ps.setInt(2, mode == MODE_NOBODY ? 0 : 1);
                ps.setString(3, me.toString());
                ps.executeUpdate();
            }
            return null;
        }), player, (p, v) -> sendMapData(p));
    }

    public static void setFriend(ServerPlayer player, UUID friend, boolean add) {
        UUID me = player.getUUID();
        if (friend.equals(me)) return;
        PhoneServer.then(PhoneServer.db().submit(c -> {
            if (add && PhoneDatabase.nameSync(c, friend) == null) return false;
            try (PreparedStatement ps = c.prepareStatement(add
                    ? "INSERT OR IGNORE INTO friends(owner, friend) VALUES (?, ?)"
                    : "DELETE FROM friends WHERE owner = ? AND friend = ?")) {
                ps.setString(1, me.toString());
                ps.setString(2, friend.toString());
                ps.executeUpdate();
            }
            return true;
        }), player, (p, ok) -> {
            if (!ok) return;
            Set<UUID> set = FRIENDS.computeIfAbsent(me, k -> ConcurrentHashMap.newKeySet());
            if (add) set.add(friend);
            else set.remove(friend);
            if (add) {
                PhoneServer.notify(friend, "settings", Component.translatable("smartphone.notify.friend_title"),
                        Component.translatable("smartphone.notify.friend_body", p.getName()));
            }
            sendMapData(p);
            MessageService.sendContacts(p);
        });
    }

    // ---------------------------------------------------------------- waypoints & map data

    private record MapRows(List<S2C.FriendEntry> friends, List<S2C.WaypointEntry> waypoints) {}

    public static void sendMapData(ServerPlayer player) {
        UUID me = player.getUUID();
        PhoneServer.then(PhoneServer.db().submit(c -> {
            List<S2C.FriendEntry> friends = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT f.friend, a.name FROM friends f JOIN accounts a ON a.uuid = f.friend WHERE f.owner = ? ORDER BY a.name_lower")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { UUID u = PhoneDatabase.uuid(rs.getString(1)); if (u != null) friends.add(new S2C.FriendEntry(u, rs.getString(2))); }
                }
            }
            List<S2C.WaypointEntry> waypoints = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, dim, x, y, z FROM waypoints WHERE owner = ? ORDER BY name COLLATE NOCASE")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        waypoints.add(new S2C.WaypointEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getInt(4), rs.getInt(5), rs.getInt(6)));
                    }
                }
            }
            return new MapRows(friends, waypoints);
        }), player, (p, rows) -> ServerPlayNetworking.send(p, new S2C.MapData(mode(me), rows.friends(), rows.waypoints())));
    }

    public static void saveWaypoint(ServerPlayer player, String rawName) {
        String name = MessageService.sanitize(rawName, 24);
        if (name.isEmpty()) return;
        if (!Cooldowns.tryUse(player.getUUID(), "waypoint", 500)) return;
        UUID me = player.getUUID();
        String dim = player.level().dimension().identifier().toString();
        int x = player.getBlockX(), y = player.getBlockY(), z = player.getBlockZ(); // the server decides the position
        int max = PhoneServer.config().maxWaypoints;
        PhoneServer.then(PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM waypoints WHERE owner = ?")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= max) return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO waypoints(owner, name, dim, x, y, z) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, me.toString());
                ps.setString(2, name);
                ps.setString(3, dim);
                ps.setInt(4, x);
                ps.setInt(5, y);
                ps.setInt(6, z);
                ps.executeUpdate();
            }
            return true;
        }), player, (p, ok) -> {
            if (!ok) p.sendOverlayMessage(Component.translatable("smartphone.map.error.too_many", max).withStyle(ChatFormatting.RED));
            sendMapData(p);
        });
    }

    public static void deleteWaypoint(ServerPlayer player, long id) {
        UUID me = player.getUUID();
        PhoneServer.then(PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM waypoints WHERE id = ? AND owner = ?")) {
                ps.setLong(1, id);
                ps.setString(2, me.toString());
                ps.executeUpdate();
            }
            return null;
        }), player, (p, v) -> sendMapData(p));
    }
}
