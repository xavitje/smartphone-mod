package nl.smartphonemod.cctv;

import io.netty.buffer.Unpooled;
import nl.smartphonemod.config.SmartphoneConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/**
 * Keeps the last 30 seconds of what happens around every camera, in memory only: entity positions, poses and
 * equipment (10 samples a second) and block changes. Nothing is written to disk. A watcher's client replays it
 * in the real world, so it looks like Minecraft footage without being video.
 */
public final class CctvRecorder {
    private CctvRecorder() {}

    public static final int FORMAT = 1;
    static final int SAMPLE_TICKS = 2;
    static final int SECONDS = 30;
    static final int MAX_FRAMES = SECONDS * 20 / SAMPLE_TICKS;

    static final int F_CROUCH = 1, F_SPRINT = 2, F_SWIM = 4, F_BABY = 8, F_SWING = 16, F_SLEEP = 32, F_FALLFLY = 64;

    record EntitySample(int id, int type, byte flags, float x, float y, float z, byte yaw, byte pitch, byte head, byte body,
                        String name, java.util.UUID uuid, ItemStack[] equipment) {}

    record Frame(long tick, long wallMs, List<EntitySample> entities) {}

    record BlockChange(long tick, long pos, int oldState, int newState) {}

    static final class Buffer {
        final BlockPos camera;
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        final ArrayDeque<BlockChange> blocks = new ArrayDeque<>();
        /** Per entity id: hash of the last equipment we stored, and the last swing we saw. */
        final Map<Integer, Integer> equipHash = new HashMap<>();
        final Map<Integer, Object> lastSwing = new HashMap<>();

        Buffer(BlockPos camera) {
            this.camera = camera;
        }
    }

    private static final Map<ServerLevel, Map<BlockPos, Buffer>> BUFFERS = new IdentityHashMap<>();
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    static void add(ServerLevel level, BlockPos pos) {
        BUFFERS.computeIfAbsent(level, k -> new HashMap<>()).putIfAbsent(pos.immutable(), new Buffer(pos.immutable()));
    }

    static void remove(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Buffer> m = BUFFERS.get(level);
        if (m != null) m.remove(pos);
    }

    static void clear() {
        BUFFERS.clear();
    }

    private static int radius() {
        return SmartphoneConfig.get().cctvRecordRadius;
    }

    private static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256f / 360f);
    }

    // ------------------------------------------------------------------ recording

    static void tick(MinecraftServer server) {
        if (BUFFERS.isEmpty() || server.getTickCount() % SAMPLE_TICKS != 0) return;
        long now = System.currentTimeMillis();
        int r = radius();
        for (var levelEntry : BUFFERS.entrySet()) {
            ServerLevel level = levelEntry.getKey();
            long tick = level.getGameTime();
            for (Buffer buf : levelEntry.getValue().values()) {
                BlockPos c = buf.camera;
                if (level.getChunkSource().getChunkNow(c.getX() >> 4, c.getZ() >> 4) == null) continue; // not loaded: nothing to see
                List<EntitySample> samples = new ArrayList<>();
                for (Entity e : level.getEntities((Entity) null, new AABB(c).inflate(r), CctvRecorder::recordable)) {
                    samples.add(sample(buf, e));
                    if (samples.size() >= 64) break;
                }
                buf.frames.addLast(new Frame(tick, now, samples));
                while (buf.frames.size() > MAX_FRAMES) buf.frames.removeFirst();
                long oldest = buf.frames.peekFirst().tick();
                while (!buf.blocks.isEmpty() && buf.blocks.peekFirst().tick() < oldest) buf.blocks.removeFirst();
                if (buf.equipHash.size() > 512) {
                    buf.equipHash.clear();
                    buf.lastSwing.clear();
                }
            }
        }
    }

    private static boolean recordable(Entity e) {
        return !e.isRemoved() && !e.isSpectator() && (e instanceof LivingEntity || e instanceof ItemEntity || e instanceof VehicleEntity);
    }

    private static EntitySample sample(Buffer buf, Entity e) {
        int flags = 0;
        if (e.isShiftKeyDown() || e.getPose() == Pose.CROUCHING) flags |= F_CROUCH;
        if (e.isSprinting()) flags |= F_SPRINT;
        if (e.isSwimming()) flags |= F_SWIM;
        float head = e.getYHeadRot(), body = e.getYRot();
        ItemStack[] equipment = null;
        if (e instanceof LivingEntity le) {
            if (le.isBaby()) flags |= F_BABY;
            if (le.isSleeping()) flags |= F_SLEEP;
            if (le.isFallFlying()) flags |= F_FALLFLY;
            body = le.yBodyRot;
            head = le.yHeadRot;
            Object swing = le.getCurrentSwing();
            if (swing != null && swing != buf.lastSwing.get(e.getId())) flags |= F_SWING;
            buf.lastSwing.put(e.getId(), swing);
            int hash = 1;
            for (EquipmentSlot s : SLOTS) hash = hash * 31 + ItemStack.hashItemAndComponents(le.getItemBySlot(s));
            Integer old = buf.equipHash.put(e.getId(), hash);
            if (old == null || old != hash) {
                equipment = new ItemStack[SLOTS.length];
                for (int i = 0; i < SLOTS.length; i++) equipment[i] = le.getItemBySlot(SLOTS[i]).copy();
            }
        } else if (e instanceof ItemEntity item) {
            int hash = ItemStack.hashItemAndComponents(item.getItem()) * 31 + item.getItem().getCount();
            Integer old = buf.equipHash.put(e.getId(), hash);
            if (old == null || old != hash) equipment = new ItemStack[]{item.getItem().copy()};
        }
        String name = null;
        java.util.UUID uuid = null;
        if (e instanceof Player p && !buf.equipHash.containsKey(-e.getId())) {
            name = p.getGameProfile().name();
            uuid = p.getUUID();
        }
        if (e instanceof Player) buf.equipHash.put(-e.getId(), 0); // remember that the name was sent
        return new EntitySample(e.getId(), BuiltInRegistries.ENTITY_TYPE.getId(e.getType()), (byte) flags,
                (float) e.getX(), (float) e.getY(), (float) e.getZ(), angle(e.getYRot()), angle(e.getXRot()), angle(head), angle(body),
                name, uuid, equipment);
    }

    /** Called (via mixin) for every block change the server sends to clients. */
    public static void blockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        Map<BlockPos, Buffer> m = BUFFERS.get(level);
        if (m == null || m.isEmpty() || old == now) return;
        int r = radius();
        for (Buffer buf : m.values()) {
            BlockPos c = buf.camera;
            if (Math.abs(c.getX() - pos.getX()) <= r && Math.abs(c.getY() - pos.getY()) <= r && Math.abs(c.getZ() - pos.getZ()) <= r) {
                buf.blocks.addLast(new BlockChange(level.getGameTime(), pos.asLong(), Block.getId(old), Block.getId(now)));
                if (buf.blocks.size() > 4096) buf.blocks.removeFirst();
            }
        }
    }

    // ------------------------------------------------------------------ export

    /**
     * The buffer as a compressed blob. The first frame repeats names and equipment of everyone in it, so a clip
     * can start anywhere.
     */
    static byte[] export(ServerLevel level, BlockPos camera, RegistryAccess access) {
        Map<BlockPos, Buffer> m = BUFFERS.get(level);
        Buffer buf = m == null ? null : m.get(camera);
        if (buf == null || buf.frames.isEmpty()) return null;
        List<Frame> frames = new ArrayList<>(buf.frames);
        // Full state for every entity's first appearance.
        Map<Integer, ItemStack[]> equip = new HashMap<>();
        Map<Integer, String[]> names = new HashMap<>();
        for (Frame f : frames) for (EntitySample s : f.entities()) {
            if (s.equipment() != null) equip.put(s.id(), s.equipment());
            if (s.name() != null) names.put(s.id(), new String[]{s.name(), s.uuid().toString()});
        }
        // Equipment/name in the oldest frames may have been sent before the window: fill in from the live entities.
        for (Frame f : frames) for (EntitySample s : f.entities()) {
            Entity e = level.getEntity(s.id());
            if (!equip.containsKey(s.id()) && e != null) {
                if (e instanceof LivingEntity le) {
                    ItemStack[] eq = new ItemStack[SLOTS.length];
                    for (int i = 0; i < SLOTS.length; i++) eq[i] = le.getItemBySlot(SLOTS[i]).copy();
                    equip.put(s.id(), eq);
                } else if (e instanceof ItemEntity item) {
                    equip.put(s.id(), new ItemStack[]{item.getItem().copy()});
                }
            }
            if (!names.containsKey(s.id()) && e instanceof Player p) names.put(s.id(), new String[]{p.getGameProfile().name(), p.getUUID().toString()});
        }

        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        out.writeVarInt(FORMAT);
        out.writeBlockPos(camera);
        out.writeVarInt(frames.size());
        long baseTick = frames.get(0).tick();
        out.writeLong(baseTick);
        out.writeLong(frames.get(0).wallMs());
        java.util.Set<Integer> introduced = new java.util.HashSet<>();
        for (Frame f : frames) {
            out.writeVarInt((int) (f.tick() - baseTick));
            out.writeVarInt((int) (f.wallMs() - frames.get(0).wallMs()));
            out.writeVarInt(f.entities().size());
            for (EntitySample s : f.entities()) {
                boolean first = introduced.add(s.id());
                ItemStack[] eq = first ? equip.get(s.id()) : s.equipment();
                String[] name = first ? names.get(s.id()) : null;
                out.writeVarInt(s.id());
                out.writeVarInt(s.type());
                out.writeByte(s.flags());
                out.writeFloat(s.x());
                out.writeFloat(s.y());
                out.writeFloat(s.z());
                out.writeByte(s.yaw());
                out.writeByte(s.pitch());
                out.writeByte(s.head());
                out.writeByte(s.body());
                out.writeBoolean(name != null);
                if (name != null) {
                    out.writeUtf(name[0], 32);
                    out.writeUtf(name[1], 40);
                }
                out.writeVarInt(eq == null ? 0 : eq.length);
                if (eq != null) for (ItemStack stack : eq) ItemStack.OPTIONAL_STREAM_CODEC.encode(out, stack);
            }
        }
        List<BlockChange> blocks = new ArrayList<>(buf.blocks);
        out.writeVarInt(blocks.size());
        for (BlockChange b : blocks) {
            out.writeVarLong(Math.max(0, b.tick() - baseTick));
            out.writeLong(b.pos());
            out.writeVarInt(b.oldState());
            out.writeVarInt(b.newState());
        }
        byte[] raw = new byte[out.readableBytes()];
        out.readBytes(raw);
        out.release();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length / 3 + 64);
        try (DeflaterOutputStream z = new DeflaterOutputStream(bytes)) {
            z.write(raw);
        } catch (java.io.IOException e) {
            return null;
        }
        return bytes.toByteArray();
    }
}
