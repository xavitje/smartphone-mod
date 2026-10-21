package nl.smartphonemod.client.cctv;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import nl.smartphonemod.mixin.LivingEntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.InflaterInputStream;

/**
 * Plays back the last 30 seconds of a camera inside the real world: recorded players, mobs and items are
 * drawn as stand-in entities (the real ones are hidden), and recorded block changes are undone and redone.
 */
public final class CctvReplay {
    private CctvReplay() {}

    static final int F_CROUCH = 1, F_SPRINT = 2, F_SWIM = 4, F_BABY = 8, F_SWING = 16, F_SLEEP = 32, F_FALLFLY = 64;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    record Sample(int id, int type, int flags, float x, float y, float z, float yaw, float pitch, float head, float body,
                  String name, UUID uuid, ItemStack[] equipment) {}

    record Frame(int tick, long wallMs, Map<Integer, Sample> entities) {}

    record BlockChange(long tick, BlockPos pos, BlockState oldState, BlockState newState) {}

    /** A decoded recording. */
    public record Recording(BlockPos camera, long startWallMs, List<Frame> frames, List<BlockChange> blocks, byte[] raw) {
        public int lastTick() {
            return frames.isEmpty() ? 0 : frames.get(frames.size() - 1).tick();
        }

        public long durationMs() {
            return lastTick() * 50L;
        }
    }

    private static Recording rec;
    private static float playhead;
    private static boolean playing;
    private static float speed = 1f;
    private static final Map<Integer, Entity> FAKES = new HashMap<>();
    private static final List<Entity> VISIBLE = new ArrayList<>();
    private static final Map<Integer, Integer> lastFrameOf = new HashMap<>();
    private static int lastFrameIndex = -1;
    /** Per entity id: (frame index, equipment) in time order. */
    private static final Map<Integer, List<Object[]>> EQUIP = new HashMap<>();
    private static final Map<Integer, String[]> NAMES = new HashMap<>();
    private static final Map<BlockPos, List<BlockChange>> CHANGES = new LinkedHashMap<>();

    public static boolean active() {
        return rec != null;
    }

    public static Recording recording() {
        return rec;
    }

    public static boolean playing() {
        return playing;
    }

    public static void playing(boolean p) {
        playing = p;
        if (p && rec != null && playhead >= rec.lastTick()) playhead = 0;
    }

    public static float speed() {
        return speed;
    }

    public static void speed(float s) {
        speed = s;
    }

    public static float playhead() {
        return playhead;
    }

    public static Iterable<Entity> entities() {
        return VISIBLE;
    }

    /** Wall-clock time (ms) of the moment shown now. */
    public static long shownWallMs() {
        if (rec == null || rec.frames().isEmpty()) return System.currentTimeMillis();
        Frame f = rec.frames().get(frameIndex(playhead));
        return rec.startWallMs() + f.wallMs() + (long) ((playhead - f.tick()) * 50);
    }

    // ------------------------------------------------------------------ decoding

    public static Recording decode(BlockPos camera, byte[] data) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        byte[] raw;
        try (InflaterInputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 3);
            in.transferTo(out);
            raw = out.toByteArray();
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(raw), mc.level.registryAccess());
        int format = buf.readVarInt();
        if (format != 1) throw new IOException("Unknown recording format " + format);
        BlockPos cam = buf.readBlockPos();
        int frameCount = buf.readVarInt();
        long baseTick = buf.readLong();
        long baseWall = buf.readLong();
        List<Frame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int tick = buf.readVarInt();
            long wall = buf.readVarInt();
            int n = buf.readVarInt();
            Map<Integer, Sample> ents = new LinkedHashMap<>();
            for (int k = 0; k < n; k++) {
                int id = buf.readVarInt();
                int type = buf.readVarInt();
                int flags = buf.readByte();
                float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
                float yaw = buf.readByte() * 360f / 256f, pitch = buf.readByte() * 360f / 256f;
                float head = buf.readByte() * 360f / 256f, body = buf.readByte() * 360f / 256f;
                String name = null;
                UUID uuid = null;
                if (buf.readBoolean()) {
                    name = buf.readUtf(32);
                    try {
                        uuid = UUID.fromString(buf.readUtf(40));
                    } catch (IllegalArgumentException e) {
                        uuid = UUID.randomUUID();
                    }
                }
                int eqCount = buf.readVarInt();
                ItemStack[] eq = null;
                if (eqCount > 0) {
                    eq = new ItemStack[eqCount];
                    for (int s = 0; s < eqCount; s++) eq[s] = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                }
                ents.put(id, new Sample(id, type, flags, x, y, z, yaw, pitch, head, body, name, uuid, eq));
            }
            frames.add(new Frame(tick, wall, ents));
        }
        int nb = buf.readVarInt();
        List<BlockChange> blocks = new ArrayList<>(nb);
        for (int i = 0; i < nb; i++) {
            long tick = buf.readVarLong();
            BlockPos pos = BlockPos.of(buf.readLong());
            BlockState o = Block.stateById(buf.readVarInt()), n = Block.stateById(buf.readVarInt());
            blocks.add(new BlockChange(tick, pos, o, n));
        }
        buf.release();
        return new Recording(cam, baseWall, frames, blocks, data);
    }

    // ------------------------------------------------------------------ playback

    public static void start(Recording r) {
        stop();
        rec = r;
        playhead = 0;
        playing = true;
        speed = 1f;
        for (int i = 0; i < r.frames().size(); i++) {
            for (Sample s : r.frames().get(i).entities().values()) {
                if (s.equipment() != null) EQUIP.computeIfAbsent(s.id(), k -> new ArrayList<>()).add(new Object[]{i, s.equipment()});
                if (s.name() != null) NAMES.put(s.id(), new String[]{s.name(), s.uuid().toString()});
            }
        }
        for (BlockChange b : r.blocks()) CHANGES.computeIfAbsent(b.pos(), k -> new ArrayList<>()).add(b);
        apply(true);
    }

    /** Back to live: real entities and the current blocks. */
    public static void stop() {
        if (rec != null) {
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                for (var e : CHANGES.entrySet()) {
                    List<BlockChange> list = e.getValue();
                    BlockState live = list.get(list.size() - 1).newState();
                    if (level.getBlockState(e.getKey()) != live) level.setBlock(e.getKey(), live, 11);
                }
            }
        }
        rec = null;
        FAKES.clear();
        VISIBLE.clear();
        EQUIP.clear();
        NAMES.clear();
        CHANGES.clear();
        lastFrameOf.clear();
        lastFrameIndex = -1;
        playing = false;
    }

    public static void seek(float tick) {
        if (rec == null) return;
        playhead = Mth.clamp(tick, 0, rec.lastTick());
        apply(true);
    }

    public static void tick() {
        if (rec == null) return;
        if (Minecraft.getInstance().level == null) {
            stop();
            return;
        }
        if (playing) {
            playhead += speed;
            if (playhead >= rec.lastTick()) {
                playhead = rec.lastTick();
                playing = false;
            }
        }
        apply(false);
        for (Entity e : VISIBLE) {
            if (e instanceof LivingEntity le) ((LivingEntityAccessor) le).smartphone$swingState().tick();
        }
    }

    private static int frameIndex(float tick) {
        List<Frame> f = rec.frames();
        int lo = 0, hi = f.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (f.get(mid).tick() <= tick) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private static ItemStack[] equipmentAt(int id, int frame) {
        List<Object[]> list = EQUIP.get(id);
        if (list == null) return null;
        ItemStack[] best = null;
        for (Object[] o : list) {
            if ((int) o[0] <= frame) best = (ItemStack[]) o[1];
            else break;
        }
        return best != null ? best : (ItemStack[]) list.get(0)[1];
    }

    private static void apply(boolean snap) {
        ClientLevel level = Minecraft.getInstance().level;
        if (rec == null || level == null || rec.frames().isEmpty()) return;
        int i = frameIndex(playhead);
        Frame a = rec.frames().get(i);
        Frame b = i + 1 < rec.frames().size() ? rec.frames().get(i + 1) : a;
        float t = b.tick() == a.tick() ? 0 : Mth.clamp((playhead - a.tick()) / (b.tick() - a.tick()), 0, 1);
        boolean newFrame = i != lastFrameIndex;
        VISIBLE.clear();
        for (Sample s : a.entities().values()) {
            Entity e = FAKES.computeIfAbsent(s.id(), id -> create(level, s));
            if (e == null) continue;
            Sample n = b.entities().getOrDefault(s.id(), s);
            double x = Mth.lerp(t, s.x(), n.x()), y = Mth.lerp(t, s.y(), n.y()), z = Mth.lerp(t, s.z(), n.z());
            double moved = Math.sqrt(Mth.square(x - e.getX()) + Mth.square(z - e.getZ()));
            if (snap || e.tickCount == 0) {
                e.setPos(x, y, z);
                e.setOldPosAndRot();
            } else {
                e.setOldPosAndRot();
                e.setPos(x, y, z);
            }
            e.tickCount++;
            float yaw = Mth.rotLerp(t, s.yaw(), n.yaw()), pitch = Mth.lerp(t, s.pitch(), n.pitch());
            e.setYRot(yaw);
            e.setXRot(pitch);
            e.setShiftKeyDown((s.flags() & F_CROUCH) != 0);
            e.setSprinting((s.flags() & F_SPRINT) != 0);
            Pose pose = (s.flags() & F_SLEEP) != 0 ? Pose.SLEEPING : (s.flags() & F_FALLFLY) != 0 ? Pose.FALL_FLYING
                    : (s.flags() & F_SWIM) != 0 ? Pose.SWIMMING : (s.flags() & F_CROUCH) != 0 ? Pose.CROUCHING : Pose.STANDING;
            e.setPose(pose);
            if (e instanceof LivingEntity le) {
                le.yBodyRotO = snap ? Mth.rotLerp(t, s.body(), n.body()) : le.yBodyRot;
                le.yBodyRot = Mth.rotLerp(t, s.body(), n.body());
                le.yHeadRotO = snap ? Mth.rotLerp(t, s.head(), n.head()) : le.yHeadRot;
                le.yHeadRot = Mth.rotLerp(t, s.head(), n.head());
                le.walkAnimation.update(snap ? 0 : (float) Math.min(moved * 4.0, 1.0), 0.4f, 1f);
                if (le instanceof Mob mob) mob.setBaby((s.flags() & F_BABY) != 0);
                if (newFrame && (s.flags() & F_SWING) != 0 && lastFrameOf.getOrDefault(s.id(), -1) != i) {
                    le.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                }
                if (newFrame || snap) {
                    ItemStack[] eq = equipmentAt(s.id(), i);
                    if (eq != null) for (int k = 0; k < Math.min(eq.length, SLOTS.length); k++) le.setItemSlot(SLOTS[k], eq[k]);
                }
            } else if (e instanceof ItemEntity item && (newFrame || snap)) {
                ItemStack[] eq = equipmentAt(s.id(), i);
                if (eq != null && eq.length > 0) item.setItem(eq[0]);
            }
            lastFrameOf.put(s.id(), i);
            VISIBLE.add(e);
        }
        lastFrameIndex = i;
        // Blocks as they were at the playhead.
        for (var entry : CHANGES.entrySet()) {
            BlockState want = null;
            for (BlockChange c : entry.getValue()) {
                if (c.tick() > playhead) {
                    want = c.oldState();
                    break;
                }
            }
            if (want == null) want = entry.getValue().get(entry.getValue().size() - 1).newState();
            if (level.getBlockState(entry.getKey()) != want) level.setBlock(entry.getKey(), want, 11);
        }
    }

    private static Entity create(ClientLevel level, Sample s) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(s.type());
        Entity e;
        if (type == EntityTypes.PLAYER) {
            String[] meta = NAMES.get(s.id());
            UUID uuid = meta == null ? UUID.randomUUID() : UUID.fromString(meta[1]);
            String name = meta == null ? "?" : meta[0];
            e = new RemotePlayer(level, new GameProfile(uuid, name));
        } else {
            if (type == null) return null;
            e = type.create(level, EntitySpawnReason.LOAD);
        }
        if (e == null) return null;
        e.setId(-100000 - s.id());
        e.setNoGravity(true);
        return e;
    }
}
