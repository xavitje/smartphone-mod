package nl.smartphonemod.cctv;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.config.SmartphoneConfig;
import nl.smartphonemod.mixin.ChunkMapInvoker;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.server.PhoneServer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Security cameras. Nothing is ever recorded to disk: when a player watches, the server simply treats the
 * camera as their "eyes" for chunk and entity tracking (see the ChunkMap mixins), so their own client renders
 * the real view. The player's body stays where it is.
 */
public final class CctvService {
    private CctvService() {}

    /** Keeps a camera's own chunk loaded and ticking (like /forceload). */
    public static final TicketType KEEP = net.minecraft.core.Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath(SmartphoneMod.MOD_ID, "cctv"), new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
    /** Loads the area around a camera while someone watches. */
    public static final TicketType VIEW = net.minecraft.core.Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath(SmartphoneMod.MOD_ID, "cctv_view"), new TicketType(60L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));

    /** Who is watching which camera. */
    public record Session(ServerLevel level, BlockPos camera, Vec3 eye, ChunkPos chunk) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static MinecraftServer server;
    private static int tick;

    // ------------------------------------------------------------------ used by the mixins (hot paths)

    public static Session session(ServerPlayer p) {
        return SESSIONS.isEmpty() ? null : SESSIONS.get(p.getUUID());
    }

    public static int viewRadius() {
        return SmartphoneConfig.get().cctvViewDistance;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            loadTickets();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            SESSIONS.clear();
            CctvRecorder.clear();
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(CctvService::tick);
        ServerTickEvents.END_SERVER_TICK.register(CctvRecorder::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> s.execute(() -> SESSIONS.remove(h.getPlayer().getUUID())));
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> s.execute(() -> sendAccess(h.getPlayer())));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer p && SESSIONS.containsKey(p.getUUID())) {
                stop(p, "smartphone.cctv.end.damage", "Je werd geraakt!");
            }
            return true;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) stop(p, null, null);
        });
    }

    private static String dim(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static ServerLevel level(String dimension) {
        if (server == null) return null;
        Identifier id = Identifier.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static void keepLoaded(ServerLevel level, BlockPos pos, boolean on) {
        if (!SmartphoneConfig.get().cctvKeepLoaded) return;
        ChunkPos c = ChunkPos.containing(pos);
        if (on) level.getChunkSource().addTicketWithRadius(KEEP, c, 2);
        else level.getChunkSource().removeTicketWithRadius(KEEP, c, 2);
    }

    /** On start: every known camera keeps its chunk loaded again. */
    private static void loadTickets() {
        if (!PhoneServer.isRunning()) return;
        PhoneServer.thenOnServer(PhoneServer.db().submit(c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT dim, x, y, z FROM cctv_cameras"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
            }
            return rows;
        }), rows -> {
            int n = 0;
            for (String[] r : rows) {
                ServerLevel level = level(r[0]);
                if (level == null) continue;
                BlockPos camPos = new BlockPos(Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3]));
                keepLoaded(level, camPos, true);
                CctvRecorder.add(level, camPos);
                n++;
            }
            SmartphoneMod.LOGGER.info("CCTV: {} camera(s){}", n, SmartphoneConfig.get().cctvKeepLoaded ? " keep their chunks loaded" : "");
        });
    }

    // ------------------------------------------------------------------ registry

    static void cameraPlaced(ServerLevel level, CctvCameraBlockEntity cam) {
        keepLoaded(level, cam.getBlockPos(), true);
        CctvRecorder.add(level, cam.getBlockPos());
        upsert(level, cam);
    }

    static void upsert(ServerLevel level, CctvCameraBlockEntity cam) {
        if (!PhoneServer.isRunning()) return;
        String d = dim(level), name = cam.name(), owner = cam.owner() == null ? "" : cam.owner().toString();
        BlockPos p = cam.getBlockPos();
        PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO cctv_cameras(dim, x, y, z, name, owner) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, d);
                ps.setInt(2, p.getX());
                ps.setInt(3, p.getY());
                ps.setInt(4, p.getZ());
                ps.setString(5, name);
                ps.setString(6, owner);
                return ps.executeUpdate();
            }
        });
    }

    static void cameraRemoved(ServerLevel level, BlockPos pos) {
        keepLoaded(level, pos, false);
        CctvRecorder.remove(level, pos);
        for (var e : new ArrayList<>(SESSIONS.entrySet())) {
            if (e.getValue().camera().equals(pos) && e.getValue().level() == level) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) stop(p, "smartphone.cctv.end.broken", "De camera is kapotgemaakt!");
            }
        }
        if (!PhoneServer.isRunning()) return;
        String d = dim(level);
        PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cctv_cameras WHERE dim = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, d);
                ps.setInt(2, pos.getX());
                ps.setInt(3, pos.getY());
                ps.setInt(4, pos.getZ());
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ app

    public static void sendAccess(ServerPlayer p) {
        ServerPlayNetworking.send(p, new S2C.CctvAccess(CctvPerms.canView(p), CctvPerms.canManage(p)));
    }

    public static void sendList(ServerPlayer p) {
        sendAccess(p);
        if (!CctvPerms.canView(p) || !PhoneServer.isRunning()) return;
        String myDim = dim(p.level());
        PhoneServer.then(PhoneServer.db().submit(c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT dim, x, y, z, name FROM cctv_cameras ORDER BY name, x")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
                }
            }
            return rows;
        }), p, (player, rows) -> {
            List<S2C.CctvCamera> out = new ArrayList<>();
            for (String[] r : rows) {
                BlockPos pos = new BlockPos(Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3]));
                ServerLevel level = level(r[0]);
                boolean online = level != null && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                        && level.getBlockEntity(pos) instanceof CctvCameraBlockEntity;
                int viewers = online && level.getBlockEntity(pos) instanceof CctvCameraBlockEntity cam ? cam.viewers() : 0;
                String name = r[4] == null || r[4].isBlank() ? "Camera " + r[1] + ", " + r[2] + ", " + r[3] : r[4];
                out.add(new S2C.CctvCamera(r[0], pos, name, online, viewers, r[0].equals(myDim)));
            }
            ServerPlayNetworking.send(player, new S2C.CctvCameras(out));
        });
    }

    public static void rename(ServerPlayer p, String dimension, BlockPos pos, String name) {
        if (!CctvPerms.canManage(p)) return;
        ServerLevel level = level(dimension);
        if (level == null || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return;
        if (level.getBlockEntity(pos) instanceof CctvCameraBlockEntity cam) {
            cam.setName(name);
            upsert(level, cam);
            sendList(p);
        }
    }

    // ------------------------------------------------------------------ watching

    private static Vec3 eye(CctvCameraBlockEntity cam) {
        return Vec3.atCenterOf(cam.getBlockPos());
    }

    public static void view(ServerPlayer p, String dimension, BlockPos pos) {
        if (!CctvPerms.canView(p)) {
            sendAccess(p);
            return;
        }
        ServerLevel level = level(dimension);
        if (level == null || level != p.level()) {
            notify(p, "smartphone.cctv.other_dim", "Deze camera staat in een andere dimensie.");
            return;
        }
        level.getChunkSource().addTicketWithRadius(VIEW, ChunkPos.containing(pos), viewRadius());
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4); // the camera's own chunk, now
        if (!(level.getBlockEntity(pos) instanceof CctvCameraBlockEntity cam)) {
            notify(p, "smartphone.cctv.gone", "Deze camera bestaat niet meer.");
            cameraRemoved(level, pos);
            return;
        }
        Session old = SESSIONS.get(p.getUUID());
        Session s = new Session(level, pos.immutable(), eye(cam), ChunkPos.containing(pos));
        SESSIONS.put(p.getUUID(), s);
        if (old != null) viewersChanged(old.level(), old.camera());
        viewersChanged(level, pos);
        retrack(p);
        ServerPlayNetworking.send(p, new S2C.CctvViewStart(pos, cam.displayName(), CctvPerms.canManage(p)));
    }

    public static void stop(ServerPlayer p, String key, String fallback) {
        Session s = SESSIONS.remove(p.getUUID());
        if (s == null) return;
        viewersChanged(s.level(), s.camera());
        retrack(p);
        ServerPlayNetworking.send(p, new S2C.CctvViewEnd(key == null ? "" : key, fallback == null ? "" : fallback));
    }

    /** Re-runs chunk and entity tracking so it follows the new "eyes". */
    private static void retrack(ServerPlayer p) {
        var chunkMap = p.level().getChunkSource().chunkMap;
        ((ChunkMapInvoker) chunkMap).smartphone$updateChunkTracking(p);
        chunkMap.move(p);
    }

    private static void viewersChanged(ServerLevel level, BlockPos pos) {
        int n = 0;
        for (Session s : SESSIONS.values()) if (s.level() == level && s.camera().equals(pos)) n++;
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                && level.getBlockEntity(pos) instanceof CctvCameraBlockEntity cam) cam.setViewers(n);
    }

    /** The last 30 seconds of the camera this player is watching. */
    public static void sendRecording(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || !CctvPerms.canView(p)) return;
        byte[] data = CctvRecorder.export(s.level(), s.camera(), p.level().registryAccess());
        if (data == null) {
            notify(p, "smartphone.cctv.no_recording", "Deze camera heeft nog niets opgenomen.");
            return;
        }
        ServerPlayNetworking.send(p, new S2C.CctvRecording(s.camera(), data));
    }

    public static void rotate(ServerPlayer p, float yaw, float pitch) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || !CctvPerms.canManage(p)) return;
        if (s.level().getBlockEntity(s.camera()) instanceof CctvCameraBlockEntity cam) cam.setView(yaw, pitch);
    }

    private static void notify(ServerPlayer p, String key, String fallback) {
        ServerPlayNetworking.send(p, new S2C.Notify("cctv", Component.translatableWithFallback("smartphone.app.cctv", "CCTV"),
                Component.translatableWithFallback(key, fallback)));
    }

    private static void tick(MinecraftServer s) {
        if (SESSIONS.isEmpty()) return;
        tick++;
        if (tick % 10 == 0) {
            // Entities near the camera are paired once their chunk reached the client; vanilla does this when a
            // player moves, but a watcher's body stands still.
            for (UUID id : new ArrayList<>(SESSIONS.keySet())) {
                ServerPlayer p = s.getPlayerList().getPlayer(id);
                if (p != null) p.level().getChunkSource().chunkMap.move(p);
            }
        }
        if (tick % 20 != 0) return;
        for (var e : new ArrayList<>(SESSIONS.entrySet())) {
            ServerPlayer p = s.getPlayerList().getPlayer(e.getKey());
            Session ses = e.getValue();
            if (p == null) {
                SESSIONS.remove(e.getKey());
                viewersChanged(ses.level(), ses.camera());
                continue;
            }
            if (p.level() != ses.level() || !PhoneServer.hasPhone(p) || !CctvPerms.canView(p)) {
                stop(p, null, null);
                continue;
            }
            ses.level().getChunkSource().addTicketWithRadius(VIEW, ses.chunk(), viewRadius());
            if (!(ses.level().getBlockEntity(ses.camera()) instanceof CctvCameraBlockEntity)) {
                stop(p, "smartphone.cctv.end.broken", "De camera is kapotgemaakt!");
            }
        }
    }

    static void message(ServerPlayer p, String key, String fallback) {
        p.sendOverlayMessage(Component.translatableWithFallback(key, fallback).withStyle(ChatFormatting.RED));
    }
}
