package nl.smartphonemod.parkour;

import com.google.gson.Gson;
import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.config.SmartphoneConfig;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.server.PhoneDatabase;
import nl.smartphonemod.server.PhoneServer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side parkour with ordinary blocks: start / checkpoints / finish markers, a timer in the action
 * bar, back-to-checkpoint, races with a countdown and leaderboards in the smartphone database.
 * Nothing here adds blocks or items, so it needs no client-side content.
 */
public final class ParkourService {
    private ParkourService() {}

    private static final Gson GSON = new Gson();

    // ------------------------------------------------------------------ state

    enum MarkerType { START, CHECKPOINT, FINISH }

    record Hit(Course course, MarkerType type, int index) {}

    enum Phase { ARMED, COUNTDOWN, RUNNING }

    static final class Run {
        final Course course;
        final ServerLevel level;
        Phase phase;
        long startNanos;
        int nextCp;
        int lastCp = -1;
        long[] splits;
        boolean unranked;
        Race race;
        /** Set when something other than the parkour itself (a command, ender pearl, chorus fruit) moved the player. */
        boolean teleportedAway;
        boolean wasSneaking;
        long lastSneak;
        Component flash;
        long flashUntil;
        int ticks;

        Run(Course course, ServerLevel level, Phase phase) {
            this.course = course;
            this.level = level;
            this.phase = phase;
            this.splits = new long[course.checkpoints.size()];
        }

        long elapsedMs() {
            return phase == Phase.RUNNING ? (System.nanoTime() - startNanos) / 1_000_000L : 0;
        }
    }

    static final class Race {
        final Course course;
        final List<UUID> players;
        final List<UUID> finished = new ArrayList<>();
        final Set<UUID> dropped = new HashSet<>();
        final Map<UUID, String> names = new HashMap<>();
        int countdown = 70;

        Race(Course course, List<UUID> players) {
            this.course = course;
            this.players = players;
        }

        boolean over() {
            for (UUID u : players) if (!finished.contains(u) && !dropped.contains(u)) return false;
            return true;
        }
    }

    record Invite(UUID from, String fromName, String course, long expires) {}

    /** Best time of one player on one course. */
    static final class Best {
        String name;
        long ms = -1;
        long[] splits = new long[0];
        int runs, wins;
    }

    private static final Map<String, Course> COURSES = new LinkedHashMap<>();
    private static final Map<String, Map<Long, Hit>> MARKERS = new HashMap<>();
    private static final Map<UUID, Run> RUNS = new HashMap<>();
    private static final Map<String, Map<UUID, Best>> BESTS = new HashMap<>();
    private static final Map<UUID, Invite> INVITES = new HashMap<>();
    /** Who already received a course's first-completion reward. */
    private static final Map<String, Set<UUID>> CLAIMED = new HashMap<>();
    private static final List<Race> RACES = new ArrayList<>();
    private static MinecraftServer server;
    private static int tick;

    // ------------------------------------------------------------------ lifecycle

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            load();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            RUNS.clear();
            RACES.clear();
            INVITES.clear();
            COURSES.clear();
            MARKERS.clear();
            BESTS.clear();
            CLAIMED.clear();
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(ParkourService::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> s.execute(() -> drop(h.getPlayer().getUUID(), null)));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) drop(p.getUUID(), Component.translatable("smartphone.parkour.cancel.death"));
        });
        // Course protection: markers always, plus the course's protected box.
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) ->
                !(player instanceof ServerPlayer sp && isProtected(level, pos) && !canBuild(sp)));
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || canBuild(sp)) return net.minecraft.world.InteractionResult.PASS;
            var stack = player.getItemInHand(hand);
            boolean placing = stack.getItem() instanceof net.minecraft.world.item.BlockItem
                    || stack.getItem() instanceof net.minecraft.world.item.BucketItem;
            BlockPos target = level.getBlockState(hit.getBlockPos()).canBeReplaced() ? hit.getBlockPos()
                    : hit.getBlockPos().relative(hit.getDirection());
            if (placing && isProtected(level, target)) {
                sp.sendOverlayMessage(Component.translatableWithFallback("smartphone.parkour.protected", "Dit parcours is beschermd: hier kun je niet bouwen of breken.").withStyle(ChatFormatting.RED));
                return net.minecraft.world.InteractionResult.FAIL;
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer sp) || canBuild(sp)) return net.minecraft.world.InteractionResult.PASS;
            if (player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BucketItem && isProtected(level, sp.blockPosition())) {
                sp.sendOverlayMessage(Component.translatableWithFallback("smartphone.parkour.protected", "Dit parcours is beschermd: hier kun je niet bouwen of breken.").withStyle(ChatFormatting.RED));
                return net.minecraft.world.InteractionResult.FAIL;
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        // No fall damage while doing a course.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer p && RUNS.containsKey(p.getUUID()) && source.is(DamageTypeTags.IS_FALL)));
    }

    private static void load() {
        if (!PhoneServer.isRunning()) return;
        PhoneServer.thenOnServer(PhoneServer.db().submit(c -> {
            List<Course> courses = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT data FROM parkour_courses ORDER BY name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        Course course = GSON.fromJson(rs.getString(1), Course.class);
                        if (course != null && course.name != null) courses.add(course);
                    } catch (RuntimeException e) {
                        SmartphoneMod.LOGGER.warn("Skipping broken parkour course", e);
                    }
                }
            }
            Map<String, Map<UUID, Best>> bests = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.course, t.uuid, COALESCE(a.name, '?'), t.best_ms, t.splits, t.runs, t.wins
                    FROM parkour_times t LEFT JOIN accounts a ON a.uuid = t.uuid""");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = PhoneDatabase.uuid(rs.getString(2));
                    if (uuid == null) continue;
                    Best b = new Best();
                    b.name = rs.getString(3);
                    long ms = rs.getLong(4);
                    b.ms = rs.wasNull() ? -1 : ms;
                    b.splits = parseSplits(rs.getString(5));
                    b.runs = rs.getInt(6);
                    b.wins = rs.getInt(7);
                    bests.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(uuid, b);
                }
            }
            Map<String, Set<UUID>> claimed = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT course, uuid FROM parkour_rewards");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = PhoneDatabase.uuid(rs.getString(2));
                    if (uuid != null) claimed.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(uuid);
                }
            }
            return new Object[]{courses, bests, claimed};
        }), result -> {
            @SuppressWarnings("unchecked") List<Course> courses = (List<Course>) result[0];
            @SuppressWarnings("unchecked") Map<String, Map<UUID, Best>> bests = (Map<String, Map<UUID, Best>>) result[1];
            COURSES.clear();
            for (Course c : courses) COURSES.put(c.name, c);
            BESTS.clear();
            BESTS.putAll(bests);
            @SuppressWarnings("unchecked") Map<String, Set<UUID>> claimed = (Map<String, Set<UUID>>) result[2];
            CLAIMED.clear();
            CLAIMED.putAll(claimed);
            rebuildMarkers();
            COURSES.values().forEach(ParkourService::refreshHologram);
            SmartphoneMod.LOGGER.info("Parkour: {} course(s) loaded", COURSES.size());
        });
    }

    private static long[] parseSplits(String s) {
        if (s == null || s.isBlank()) return new long[0];
        String[] parts = s.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = -1;
            }
        }
        return out;
    }

    static void rebuildMarkers() {
        MARKERS.clear();
        for (Course c : COURSES.values()) {
            Map<Long, Hit> map = MARKERS.computeIfAbsent(c.dimension, k -> new HashMap<>());
            if (c.start != null) map.put(c.start.packed(), new Hit(c, MarkerType.START, -1));
            for (int i = 0; i < c.checkpoints.size(); i++) map.put(c.checkpoints.get(i).packed(), new Hit(c, MarkerType.CHECKPOINT, i));
            if (c.finish != null) map.put(c.finish.packed(), new Hit(c, MarkerType.FINISH, -1));
        }
    }

    static void saveCourse(Course c) {
        rebuildMarkers();
        if (!PhoneServer.isRunning()) return;
        String json = GSON.toJson(c);
        String name = c.name;
        PhoneServer.db().submit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO parkour_courses(name, data) VALUES (?, ?)")) {
                ps.setString(1, name);
                ps.setString(2, json);
                return ps.executeUpdate();
            }
        });
    }

    static void deleteCourse(Course c) {
        COURSES.remove(c.name);
        BESTS.remove(c.name);
        CLAIMED.remove(c.name);
        removeHologram(c);
        RUNS.values().removeIf(r -> r.course == c);
        RACES.removeIf(r -> r.course == c);
        rebuildMarkers();
        if (!PhoneServer.isRunning()) return;
        String name = c.name;
        PhoneServer.db().submit(conn -> {
            try (PreparedStatement r = conn.prepareStatement("DELETE FROM parkour_rewards WHERE course = ?")) {
                r.setString(1, name);
                r.executeUpdate();
            }
            try (PreparedStatement a = conn.prepareStatement("DELETE FROM parkour_courses WHERE name = ?");
                 PreparedStatement b = conn.prepareStatement("DELETE FROM parkour_times WHERE course = ?")) {
                a.setString(1, name);
                b.setString(1, name);
                return a.executeUpdate() + b.executeUpdate();
            }
        });
    }

    static void resetTimes(Course c, UUID only) {
        Map<UUID, Best> map = BESTS.get(c.name);
        if (map != null) {
            if (only == null) map.clear();
            else map.remove(only);
        }
        refreshHologram(c);
        if (!PhoneServer.isRunning()) return;
        String name = c.name;
        String u = only == null ? null : only.toString();
        PhoneServer.db().submit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(u == null ? "DELETE FROM parkour_times WHERE course = ?"
                    : "DELETE FROM parkour_times WHERE course = ? AND uuid = ?")) {
                ps.setString(1, name);
                if (u != null) ps.setString(2, u);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ queries (commands, phone)

    /** Admins in creative mode may still build on a course. */
    static boolean canBuild(ServerPlayer p) {
        return p.isCreative() && p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    static boolean isProtected(Level level, BlockPos pos) {
        String dim = level.dimension().identifier().toString();
        Map<Long, Hit> map = MARKERS.get(dim);
        if (map != null && map.containsKey(pos.asLong())) return true;
        for (Course c : COURSES.values()) if (c.protect != null && c.dimension.equals(dim) && c.inProtected(pos)) return true;
        return false;
    }

    // ------------------------------------------------------------------ rewards

    static boolean claimed(Course c, UUID uuid) {
        return CLAIMED.getOrDefault(c.name, Set.of()).contains(uuid);
    }

    private static com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> jsonOps() {
        return server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
    }

    static String encodeItem(net.minecraft.world.item.ItemStack stack) {
        return net.minecraft.world.item.ItemStack.CODEC.encodeStart(jsonOps(), stack).getOrThrow().toString();
    }

    static net.minecraft.world.item.ItemStack decodeItem(String json) {
        try {
            return net.minecraft.world.item.ItemStack.CODEC.parse(jsonOps(), com.google.gson.JsonParser.parseString(json))
                    .result().orElse(net.minecraft.world.item.ItemStack.EMPTY);
        } catch (RuntimeException e) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    /** "5x Diamond, 1x Elytra + 100 ◆" */
    static Component rewardText(Course c) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (String json : c.rewardItems) {
            var stack = decodeItem(json);
            if (stack.isEmpty()) continue;
            if (!first) out.append(", ");
            out.append(Component.literal(stack.getCount() + "x ")).append(stack.getHoverName());
            first = false;
        }
        if (c.rewardMoney > 0) {
            if (!first) out.append(" + ");
            out.append(Component.literal(c.rewardMoney + " ◆"));
            first = false;
        }
        return first ? Component.translatableWithFallback("smartphone.parkour.reward.none", "geen") : out;
    }

    private static void giveReward(ServerPlayer p, Course c) {
        if (!c.hasReward() || claimed(c, p.getUUID())) return;
        UUID uuid = p.getUUID();
        CLAIMED.computeIfAbsent(c.name, k -> new HashSet<>()).add(uuid);
        for (String json : c.rewardItems) {
            var stack = decodeItem(json);
            if (!stack.isEmpty()) p.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        String course = c.name, memo = c.display;
        long money = c.rewardMoney;
        if (PhoneServer.isRunning()) {
            PhoneServer.db().transaction(tx -> {
                try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO parkour_rewards(course, uuid, ts) VALUES (?,?,?)")) {
                    ps.setString(1, course);
                    ps.setString(2, uuid.toString());
                    ps.setLong(3, System.currentTimeMillis());
                    if (ps.executeUpdate() == 0) return false; // already claimed (should not happen)
                }
                if (money > 0) {
                    PhoneDatabase.credit(tx, uuid, money);
                    PhoneDatabase.log(tx, "PARKOUR", null, uuid, money, 0, memo);
                }
                return true;
            }).whenComplete((ok, err) -> {
                if (err != null) SmartphoneMod.LOGGER.error("Parkour reward failed", err);
                else if (money > 0 && Boolean.TRUE.equals(ok) && server != null) server.execute(() -> {
                    ServerPlayer now = server.getPlayerList().getPlayer(uuid);
                    if (now != null) ServerPlayNetworking.send(now, new S2C.Notify("bank", Component.translatable("smartphone.app.bank"),
                            Component.translatableWithFallback("smartphone.parkour.reward.money", "%s ◆ bijgeschreven voor parcours %s", money, memo)));
                });
            });
        }
        chat(p, Component.translatableWithFallback("smartphone.parkour.reward.got", "🎁 Beloning voor je eerste keer: %s", rewardText(c)).withStyle(ChatFormatting.LIGHT_PURPLE));
        sound(p, SoundEvents.PLAYER_LEVELUP, 1f, 1.6f);
    }

    static void resetClaims(Course c, UUID only) {
        Set<UUID> set = CLAIMED.get(c.name);
        if (set != null) {
            if (only == null) set.clear();
            else set.remove(only);
        }
        if (!PhoneServer.isRunning()) return;
        String name = c.name, u = only == null ? null : only.toString();
        PhoneServer.db().submit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(u == null ? "DELETE FROM parkour_rewards WHERE course = ?"
                    : "DELETE FROM parkour_rewards WHERE course = ? AND uuid = ?")) {
                ps.setString(1, name);
                if (u != null) ps.setString(2, u);
                return ps.executeUpdate();
            }
        });
    }

    static Run runOf(UUID uuid) {
        return RUNS.get(uuid);
    }

    static Map<String, Course> courses() {
        return COURSES;
    }

    static Course course(String name) {
        return name == null ? null : COURSES.get(name.toLowerCase(Locale.ROOT));
    }

    /** Players with a best time, fastest first. */
    static List<Map.Entry<UUID, Best>> leaderboard(Course c) {
        Map<UUID, Best> map = BESTS.getOrDefault(c.name, Map.of());
        List<Map.Entry<UUID, Best>> out = new ArrayList<>();
        for (var e : map.entrySet()) if (e.getValue().ms >= 0) out.add(e);
        out.sort(Comparator.comparingLong(e -> e.getValue().ms));
        return out;
    }

    static Best best(Course c, UUID uuid) {
        return BESTS.getOrDefault(c.name, Map.of()).get(uuid);
    }

    static int rank(Course c, UUID uuid) {
        List<Map.Entry<UUID, Best>> board = leaderboard(c);
        for (int i = 0; i < board.size(); i++) if (board.get(i).getKey().equals(uuid)) return i + 1;
        return 0;
    }

    public static String time(long ms) {
        if (ms < 0) return "-";
        long m = ms / 60000, s = (ms / 1000) % 60, cs = (ms % 1000) / 10;
        return String.format(Locale.ROOT, "%d:%02d.%02d", m, s, cs);
    }

    private static String delta(long ms) {
        return (ms < 0 ? "-" : "+") + String.format(Locale.ROOT, "%.2f", Math.abs(ms) / 1000.0);
    }

    // ------------------------------------------------------------------ feedback helpers

    private static void sound(ServerPlayer p, Holder<SoundEvent> sound, float volume, float pitch) {
        p.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS, p.getX(), p.getY(), p.getZ(), volume, pitch, p.getRandom().nextLong()));
    }

    private static void sound(ServerPlayer p, SoundEvent sound, float volume, float pitch) {
        sound(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch);
    }

    private static void title(ServerPlayer p, Component title, Component subtitle, int stay) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(0, stay, 8));
        p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle == null ? Component.empty() : subtitle));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    private static void chat(ServerPlayer p, Component message) {
        p.sendSystemMessage(message);
    }

    private static void flash(Run run, Component message) {
        run.flash = message;
        run.flashUntil = System.currentTimeMillis() + 2500;
    }

    // ------------------------------------------------------------------ the tick

    private static void tick(MinecraftServer s) {
        if (COURSES.isEmpty() && RUNS.isEmpty()) return;
        tick++;
        INVITES.values().removeIf(i -> System.currentTimeMillis() > i.expires());
        for (Race race : new ArrayList<>(RACES)) tickRace(s, race);
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            try {
                tickPlayer(p);
            } catch (RuntimeException e) {
                SmartphoneMod.LOGGER.error("Parkour tick failed for {}", p.getName().getString(), e);
                RUNS.remove(p.getUUID());
            }
        }
        if (tick % 20 == 0) markerParticles(s);
        if (tick % 200 == 0) COURSES.values().forEach(ParkourService::refreshHologram);
    }

    private static Hit markerAt(ServerPlayer p) {
        Map<Long, Hit> map = MARKERS.get(p.level().dimension().identifier().toString());
        if (map == null) return null;
        BlockPos feet = p.blockPosition();
        Hit h = map.get(feet.asLong());
        if (h == null) h = map.get(feet.below().asLong());
        return h;
    }

    private static boolean canPlay(ServerPlayer p) {
        return !p.isCreative() && !p.isSpectator() && !p.getAbilities().flying && !p.isFallFlying() && !p.isPassenger();
    }

    private static void tickPlayer(ServerPlayer p) {
        UUID uuid = p.getUUID();
        Run run = RUNS.get(uuid);
        Hit hit = markerAt(p);
        if (run == null) {
            if (hit != null && hit.type() == MarkerType.START && hit.course().playable() && canPlay(p)) arm(p, hit.course());
            return;
        }
        if (run.level != p.level()) {
            drop(uuid, Component.translatable("smartphone.parkour.cancel.left"));
            return;
        }
        run.ticks++;
        switch (run.phase) {
            case ARMED -> {
                if (hit != null && hit.type() == MarkerType.START) {
                    if (hit.course() != run.course) arm(p, hit.course());
                    else if (run.ticks % 20 == 1) p.sendOverlayMessage(Component.translatable("smartphone.parkour.armed",
                            run.course.display).withStyle(ChatFormatting.GREEN));
                } else if (!canPlay(p)) {
                    RUNS.remove(uuid);
                } else {
                    start(p, run);
                }
            }
            case COUNTDOWN -> {
                Vec3 at = standPos(run.level, run.course.start);
                if (p.position().distanceToSqr(at) > 0.6) teleport(p, run, run.course.start);
            }
            case RUNNING -> running(p, run, hit);
        }
    }

    private static void arm(ServerPlayer p, Course course) {
        Run old = RUNS.get(p.getUUID());
        if (old != null && old.race != null) return;
        boolean fresh = old == null || old.course != course;
        RUNS.put(p.getUUID(), new Run(course, p.level(), Phase.ARMED));
        p.sendOverlayMessage(Component.translatable("smartphone.parkour.armed", course.display).withStyle(ChatFormatting.GREEN));
        if (fresh && course.hasReward() && !claimed(course, p.getUUID())) {
            chat(p, Component.translatableWithFallback("smartphone.parkour.reward.hint", "🎁 Haal dit parcours voor het eerst en je krijgt: %s", rewardText(course)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        sound(p, SoundEvents.NOTE_BLOCK_HAT, 0.6f, 1.2f);
    }

    private static void start(ServerPlayer p, Run run) {
        run.phase = Phase.RUNNING;
        run.startNanos = System.nanoTime();
        run.nextCp = 0;
        run.lastCp = -1;
        run.unranked = false;
        run.ticks = 0;
        sound(p, SoundEvents.NOTE_BLOCK_PLING, 0.8f, 1.4f);
    }

    private static void running(ServerPlayer p, Run run, Hit hit) {
        UUID uuid = p.getUUID();
        if (!canPlay(p)) {
            drop(uuid, Component.translatable("smartphone.parkour.cancel.flying"));
            return;
        }
        if (run.teleportedAway) {
            drop(uuid, Component.translatable("smartphone.parkour.cancel.teleport"));
            return;
        }
        if (run.elapsedMs() > 60L * 60 * 1000) {
            drop(uuid, Component.translatable("smartphone.parkour.cancel.timeout"));
            return;
        }
        if (!run.unranked && (p.hasEffect(MobEffects.SPEED) || p.hasEffect(MobEffects.JUMP_BOOST)
                || p.hasEffect(MobEffects.LEVITATION) || p.hasEffect(MobEffects.SLOW_FALLING))) {
            run.unranked = true;
            chat(p, Component.translatable("smartphone.parkour.unranked").withStyle(ChatFormatting.YELLOW));
        }

        if (hit != null && hit.course() == run.course) {
            switch (hit.type()) {
                case START -> {
                    if (run.race == null) {
                        run.phase = Phase.ARMED;
                        run.ticks = 0;
                        p.sendOverlayMessage(Component.translatable("smartphone.parkour.armed", run.course.display).withStyle(ChatFormatting.GREEN));
                        return;
                    }
                }
                case CHECKPOINT -> {
                    if (hit.index() == run.nextCp) checkpoint(p, run, hit.index());
                    else if (hit.index() > run.nextCp && run.ticks % 20 == 0) flash(run, Component.translatable(
                            "smartphone.parkour.missed", run.nextCp + 1).withStyle(ChatFormatting.RED));
                }
                case FINISH -> {
                    if (run.nextCp >= run.course.checkpoints.size()) {
                        finish(p, run);
                        return;
                    } else if (run.ticks % 20 == 0) {
                        flash(run, Component.translatable("smartphone.parkour.missed", run.nextCp + 1).withStyle(ChatFormatting.RED));
                    }
                }
            }
        }

        // Back to the last checkpoint: falling too low, water/lava, or sneaking twice quickly.
        boolean back = p.getY() < run.course.effectiveResetY() || p.isInLava() || (run.course.waterReset && p.isInWater());
        boolean sneaking = p.isShiftKeyDown();
        if (sneaking && !run.wasSneaking) {
            long now = System.currentTimeMillis();
            if (now - run.lastSneak < 400) {
                back = true;
                run.lastSneak = 0;
            } else {
                run.lastSneak = now;
            }
        }
        run.wasSneaking = sneaking;
        if (back) {
            backToCheckpoint(p, run);
            return;
        }
        if (run.ticks % 2 == 0) hud(p, run);
    }

    private static void checkpoint(ServerPlayer p, Run run, int index) {
        long ms = run.elapsedMs();
        run.splits[index] = ms;
        run.lastCp = index;
        run.nextCp = index + 1;
        MutableComponent msg = Component.translatable("smartphone.parkour.checkpoint", index + 1, run.course.checkpoints.size(), time(ms))
                .withStyle(ChatFormatting.AQUA);
        Best best = best(run.course, p.getUUID());
        if (best != null && best.ms >= 0 && index < best.splits.length && best.splits[index] >= 0) {
            long d = ms - best.splits[index];
            msg.append(Component.literal("  " + delta(d)).withStyle(d <= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        flash(run, msg);
        sound(p, SoundEvents.NOTE_BLOCK_CHIME, 0.8f, 1.0f + 0.5f * (index + 1) / Math.max(1, run.course.checkpoints.size()));
        hud(p, run);
    }

    private static void finish(ServerPlayer p, Run run) {
        long ms = run.elapsedMs();
        UUID uuid = p.getUUID();
        RUNS.remove(uuid);
        Course c = run.course;
        Best before = best(c, uuid);
        long oldBest = before == null ? -1 : before.ms;
        List<Map.Entry<UUID, Best>> board = leaderboard(c);
        long record = board.isEmpty() ? -1 : board.get(0).getValue().ms;
        boolean pb = !run.unranked && (oldBest < 0 || ms < oldBest);
        boolean serverRecord = !run.unranked && (record < 0 || ms < record);
        boolean win = run.race != null && run.race.finished.isEmpty();

        // Memory first (holograms, phone), then the database.
        Best b = BESTS.computeIfAbsent(c.name, k -> new HashMap<>()).computeIfAbsent(uuid, k -> new Best());
        b.name = p.getName().getString();
        b.runs++;
        if (win) b.wins++;
        if (pb) {
            b.ms = ms;
            b.splits = run.splits.clone();
        }
        store(c, uuid, b, pb);

        Component sub;
        if (run.unranked) sub = Component.translatable("smartphone.parkour.finish.unranked").withStyle(ChatFormatting.GRAY);
        else if (serverRecord) sub = Component.translatable("smartphone.parkour.finish.record").withStyle(ChatFormatting.GOLD);
        else if (pb) sub = Component.translatable("smartphone.parkour.finish.pb").withStyle(ChatFormatting.GREEN);
        else sub = Component.translatable("smartphone.parkour.finish.slower", delta(ms - oldBest)).withStyle(ChatFormatting.RED);
        title(p, Component.literal(time(ms)).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD), sub, 50);
        chat(p, Component.translatable("smartphone.parkour.finish.chat", c.display, time(ms)).withStyle(ChatFormatting.GREEN)
                .append(Component.literal(" ").append(sub)));
        sound(p, serverRecord ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP, 0.8f, 1.0f);
        run.level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 40, 0.5, 0.8, 0.5, 0.3);
        if (serverRecord && server != null) {
            Component news = Component.translatable("smartphone.parkour.record_broadcast", p.getName().getString(), c.display, time(ms))
                    .withStyle(ChatFormatting.GOLD);
            for (ServerPlayer other : server.getPlayerList().getPlayers()) if (other != p) other.sendSystemMessage(news);
        }
        if (!run.unranked) giveReward(p, c);
        if (run.race != null) raceFinished(run.race, p, ms);
        refreshHologram(c);
    }

    private static void store(Course c, UUID uuid, Best b, boolean pb) {
        if (!PhoneServer.isRunning()) return;
        String course = c.name, u = uuid.toString();
        long ms = b.ms;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.splits.length; i++) sb.append(i == 0 ? "" : ",").append(b.splits[i]);
        String splits = sb.toString();
        int runs = b.runs, wins = b.wins;
        PhoneServer.db().submit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO parkour_times(course, uuid, best_ms, splits, runs, wins, ts) VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT(course, uuid) DO UPDATE SET best_ms = excluded.best_ms, splits = excluded.splits,
                        runs = excluded.runs, wins = excluded.wins, ts = CASE WHEN ? THEN excluded.ts ELSE ts END""")) {
                ps.setString(1, course);
                ps.setString(2, u);
                if (ms >= 0) ps.setLong(3, ms);
                else ps.setNull(3, java.sql.Types.INTEGER);
                ps.setString(4, splits);
                ps.setInt(5, runs);
                ps.setInt(6, wins);
                ps.setLong(7, System.currentTimeMillis());
                ps.setBoolean(8, pb);
                return ps.executeUpdate();
            }
        });
    }

    private static void hud(ServerPlayer p, Run run) {
        MutableComponent line = Component.literal("⏱ " + time(run.elapsedMs())).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
        int cps = run.course.checkpoints.size();
        if (cps > 0) line.append(Component.literal("   CP " + run.nextCp + "/" + cps).withStyle(ChatFormatting.AQUA));
        if (run.race != null) {
            // Best other racer still running (or finished).
            String leader = null;
            int leaderCp = -1;
            for (UUID u : run.race.players) {
                if (u.equals(p.getUUID()) || run.race.dropped.contains(u)) continue;
                int cp = run.race.finished.contains(u) ? Integer.MAX_VALUE : (RUNS.get(u) == null ? -1 : RUNS.get(u).nextCp);
                if (cp > leaderCp) {
                    leaderCp = cp;
                    leader = run.race.names.getOrDefault(u, "?");
                }
            }
            if (leader != null) line.append(Component.literal("   ⚑ " + leader + " "
                    + (leaderCp == Integer.MAX_VALUE ? "✔" : "CP " + Math.max(0, leaderCp) + "/" + cps)).withStyle(ChatFormatting.GOLD));
        } else {
            Best best = best(run.course, p.getUUID());
            if (best != null && best.ms >= 0) line.append(Component.literal("   PB " + time(best.ms)).withStyle(ChatFormatting.GRAY));
        }
        if (run.unranked) line.append(Component.translatable("smartphone.parkour.practice").withStyle(ChatFormatting.YELLOW));
        if (run.flash != null && System.currentTimeMillis() < run.flashUntil) {
            line = Component.empty().append(run.flash).append(Component.literal("   ")).append(line);
        }
        p.sendOverlayMessage(line);
    }

    /** Where a player stands on a marker: on top of a solid block, or in a plate/carpet. */
    private static Vec3 standPos(ServerLevel level, Course.Marker m) {
        BlockPos pos = m.pos();
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        double top = shape.isEmpty() ? 0 : shape.max(net.minecraft.core.Direction.Axis.Y);
        return new Vec3(pos.getX() + 0.5, pos.getY() + top, pos.getZ() + 0.5);
    }

    private static void teleport(ServerPlayer p, Run run, Course.Marker m) {
        Vec3 at = standPos(run.level, m);
        OWN_TELEPORT = true;
        try {
            p.teleportTo(run.level, at.x, at.y, at.z, Set.of(), m.yaw, p.getXRot(), false);
        } finally {
            OWN_TELEPORT = false;
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
        p.clearFire();
    }

    private static boolean OWN_TELEPORT;

    /**
     * Called (from a mixin) for every server-side teleport of a player. Earlier versions guessed teleports from how far
     * a player moved in one tick, which also fired on fast falls when several movement packets arrived in the same tick.
     * Vanilla's own position corrections teleport to (almost) the current spot, so those are ignored.
     */
    public static void onTeleport(ServerPlayer p, Vec3 from, Vec3 to) {
        if (OWN_TELEPORT) return;
        Run run = RUNS.get(p.getUUID());
        if (run != null && run.phase == Phase.RUNNING && from.distanceToSqr(to) > 2 * 2) run.teleportedAway = true;
    }

    static void backToCheckpoint(ServerPlayer p, Run run) {
        Course.Marker m = run.lastCp < 0 ? run.course.start : run.course.checkpoints.get(run.lastCp);
        teleport(p, run, m);
        sound(p, SoundEvents.ENDERMAN_TELEPORT, 0.4f, 1.6f);
        flash(run, Component.translatable("smartphone.parkour.back").withStyle(ChatFormatting.YELLOW));
    }

    /** Ends a player's run (and race entry). */
    static void drop(UUID uuid, Component reason) {
        Run run = RUNS.remove(uuid);
        INVITES.remove(uuid);
        if (run == null) return;
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(uuid);
        if (p != null && reason != null) {
            p.sendOverlayMessage(Component.translatable("smartphone.parkour.cancelled").append(": ").append(reason).withStyle(ChatFormatting.RED));
        }
        if (run.race != null && !run.race.finished.contains(uuid)) {
            run.race.dropped.add(uuid);
            raceMessage(run.race, Component.translatable("smartphone.parkour.race.dropped", run.race.names.getOrDefault(uuid, "?"))
                    .withStyle(ChatFormatting.GRAY));
            if (run.race.over()) endRace(run.race);
        }
    }

    // ------------------------------------------------------------------ commands from players

    static void leave(ServerPlayer p) {
        if (!RUNS.containsKey(p.getUUID())) {
            chat(p, Component.translatable("smartphone.parkour.not_running").withStyle(ChatFormatting.GRAY));
            return;
        }
        drop(p.getUUID(), Component.translatable("smartphone.parkour.cancel.left"));
    }

    static void reset(ServerPlayer p) {
        Run run = RUNS.get(p.getUUID());
        if (run == null || run.phase != Phase.RUNNING) {
            chat(p, Component.translatable("smartphone.parkour.not_running").withStyle(ChatFormatting.GRAY));
            return;
        }
        backToCheckpoint(p, run);
    }

    // ------------------------------------------------------------------ races

    private static int raceRadius() {
        return SmartphoneConfig.get().parkourRaceRadius;
    }

    private static boolean nearStart(ServerPlayer p, Course c) {
        if (!p.level().dimension().identifier().toString().equals(c.dimension)) return false;
        double r = raceRadius();
        return p.position().distanceToSqr(Vec3.atCenterOf(c.start.pos())) <= r * r;
    }

    /** /parkour race <course> <player> or the phone's "challenge". */
    public static void invite(ServerPlayer from, String courseName, ServerPlayer target) {
        Course c = course(courseName);
        if (c == null || !c.playable()) {
            chat(from, Component.translatable("smartphone.parkour.unknown", courseName).withStyle(ChatFormatting.RED));
            return;
        }
        if (target == null || target == from) {
            chat(from, Component.translatable("smartphone.parkour.race.no_target").withStyle(ChatFormatting.RED));
            return;
        }
        if (inRace(from.getUUID()) || inRace(target.getUUID())) {
            chat(from, Component.translatable("smartphone.parkour.race.busy").withStyle(ChatFormatting.RED));
            return;
        }
        INVITES.put(target.getUUID(), new Invite(from.getUUID(), from.getName().getString(), c.name, System.currentTimeMillis() + 60_000));
        chat(from, Component.translatable("smartphone.parkour.race.sent", target.getName().getString(), c.display).withStyle(ChatFormatting.GREEN));
        BlockPos s = c.start.pos();
        Component accept = Component.translatable("smartphone.parkour.race.accept_button").withStyle(st -> st.withColor(ChatFormatting.GREEN)
                .withBold(true).withClickEvent(new ClickEvent.RunCommand("/parkour accept"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("/parkour accept"))));
        Component deny = Component.translatable("smartphone.parkour.race.deny_button").withStyle(st -> st.withColor(ChatFormatting.RED)
                .withBold(true).withClickEvent(new ClickEvent.RunCommand("/parkour deny")));
        target.sendSystemMessage(Component.translatable("smartphone.parkour.race.invite", from.getName().getString(), c.display,
                s.getX(), s.getY(), s.getZ()).withStyle(ChatFormatting.GOLD).append(" ").append(accept).append(" ").append(deny));
        ServerPlayNetworking.send(target, new S2C.Notify("parkour", Component.translatable("smartphone.app.parkour"),
                Component.translatable("smartphone.parkour.race.invite_short", from.getName().getString(), c.display)));
        sound(target, SoundEvents.NOTE_BLOCK_BELL, 0.8f, 1.2f);
    }

    public static void respond(ServerPlayer target, boolean accept) {
        Invite inv = INVITES.remove(target.getUUID());
        if (inv == null) {
            chat(target, Component.translatable("smartphone.parkour.race.no_invite").withStyle(ChatFormatting.GRAY));
            return;
        }
        ServerPlayer from = server.getPlayerList().getPlayer(inv.from());
        if (!accept) {
            if (from != null) chat(from, Component.translatable("smartphone.parkour.race.denied", target.getName().getString()).withStyle(ChatFormatting.GRAY));
            chat(target, Component.translatable("smartphone.parkour.race.you_denied").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (from == null) {
            chat(target, Component.translatable("smartphone.parkour.race.gone").withStyle(ChatFormatting.RED));
            return;
        }
        startRace(course(inv.course()), List.of(from, target), target);
    }

    /** /parkour race <course> without a player: everyone standing near the start joins. */
    static void openRace(ServerPlayer starter, String courseName) {
        Course c = course(courseName);
        if (c == null || !c.playable()) {
            chat(starter, Component.translatable("smartphone.parkour.unknown", courseName).withStyle(ChatFormatting.RED));
            return;
        }
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!inRace(p.getUUID()) && canPlay(p) && p.level().dimension().identifier().toString().equals(c.dimension)
                    && p.position().distanceToSqr(Vec3.atCenterOf(c.start.pos())) <= 8 * 8) near.add(p);
        }
        if (!near.contains(starter)) near.add(0, starter);
        if (near.size() < 2) {
            chat(starter, Component.translatable("smartphone.parkour.race.need_two").withStyle(ChatFormatting.RED));
            return;
        }
        startRace(c, near, starter);
    }

    private static boolean inRace(UUID uuid) {
        Run r = RUNS.get(uuid);
        return r != null && r.race != null;
    }

    static void startRace(Course c, List<ServerPlayer> players, ServerPlayer reportTo) {
        if (c == null || !c.playable()) return;
        for (ServerPlayer p : players) {
            if (!nearStart(p, c)) {
                BlockPos s = c.start.pos();
                Component msg = Component.translatable("smartphone.parkour.race.too_far", p.getName().getString(), raceRadius(),
                        s.getX(), s.getY(), s.getZ()).withStyle(ChatFormatting.RED);
                for (ServerPlayer q : players) chat(q, msg);
                return;
            }
            if (!canPlay(p)) {
                for (ServerPlayer q : players) chat(q, Component.translatable("smartphone.parkour.race.cannot_play", p.getName().getString())
                        .withStyle(ChatFormatting.RED));
                return;
            }
        }
        List<UUID> ids = new ArrayList<>();
        for (ServerPlayer p : players) ids.add(p.getUUID());
        Race race = new Race(c, ids);
        for (ServerPlayer p : players) {
            race.names.put(p.getUUID(), p.getName().getString());
            RUNS.remove(p.getUUID());
            Run run = new Run(c, p.level(), Phase.COUNTDOWN);
            run.race = race;
            RUNS.put(p.getUUID(), run);
            teleport(p, run, c.start);
            INVITES.remove(p.getUUID());
        }
        RACES.add(race);
        raceMessage(race, Component.translatable("smartphone.parkour.race.starting", c.display, String.join(", ", race.names.values()))
                .withStyle(ChatFormatting.GOLD));
    }

    private static void tickRace(MinecraftServer s, Race race) {
        if (race.countdown <= 0) return;
        race.countdown--;
        int left = race.countdown;
        if (left == 60 || left == 40 || left == 20) {
            String n = Integer.toString(left / 20);
            for (ServerPlayer p : online(race)) {
                title(p, Component.literal(n).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), null, 18);
                sound(p, SoundEvents.NOTE_BLOCK_HAT, 1f, 1f);
            }
        } else if (left == 0) {
            long now = System.nanoTime();
            for (ServerPlayer p : online(race)) {
                Run run = RUNS.get(p.getUUID());
                if (run == null || run.race != race) continue;
                run.phase = Phase.RUNNING;
                run.startNanos = now;
                run.ticks = 0;
                title(p, Component.translatable("smartphone.parkour.race.go").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), null, 20);
                sound(p, SoundEvents.NOTE_BLOCK_PLING, 1f, 2f);
            }
        }
    }

    private static List<ServerPlayer> online(Race race) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) return out;
        for (UUID u : race.players) {
            ServerPlayer p = server.getPlayerList().getPlayer(u);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static void raceMessage(Race race, Component msg) {
        for (ServerPlayer p : online(race)) p.sendSystemMessage(msg);
    }

    private static void raceFinished(Race race, ServerPlayer p, long ms) {
        race.finished.add(p.getUUID());
        int place = race.finished.size();
        if (place == 1) {
            title(p, Component.translatable("smartphone.parkour.race.won").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal(time(ms)), 60);
            for (ServerPlayer other : online(race)) {
                if (other == p) continue;
                title(other, Component.translatable("smartphone.parkour.race.lost_title", p.getName().getString()).withStyle(ChatFormatting.RED),
                        Component.literal(time(ms)), 40);
            }
        }
        raceMessage(race, Component.translatable("smartphone.parkour.race.place", place, p.getName().getString(), time(ms))
                .withStyle(place == 1 ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
        if (race.over()) endRace(race);
    }

    private static void endRace(Race race) {
        RACES.remove(race);
        for (UUID u : race.players) {
            Run r = RUNS.get(u);
            if (r != null && r.race == race) RUNS.remove(u);
        }
    }

    // ------------------------------------------------------------------ particles & holograms

    private static void markerParticles(MinecraftServer s) {
        if (!SmartphoneConfig.get().parkourMarkerParticles) return;
        for (Course c : COURSES.values()) {
            ServerLevel level = level(c.dimension);
            if (level == null) continue;
            show(level, c.start, ParticleTypes.HAPPY_VILLAGER);
            for (Course.Marker m : c.checkpoints) show(level, m, ParticleTypes.ELECTRIC_SPARK);
            show(level, c.finish, ParticleTypes.FIREWORK);
        }
    }

    private static void show(ServerLevel level, Course.Marker m, ParticleOptions type) {
        if (m == null) return;
        Vec3 at = new Vec3(m.x + 0.5, m.y + 0.5, m.z + 0.5);
        boolean near = false;
        for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(at) < 24 * 24) near = true;
        if (!near) return;
        if (level.getChunkSource().getChunkNow(m.x >> 4, m.z >> 4) == null) return;
        Vec3 top = standPos(level, m);
        level.sendParticles(type, top.x, top.y + 0.2, top.z, 4, 0.25, 0.1, 0.25, 0.01);
    }

    static ServerLevel level(String dimension) {
        if (server == null) return null;
        Identifier id = Identifier.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    static Component boardText(Course c) {
        MutableComponent text = Component.literal("🏃 " + c.display + "\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        List<Map.Entry<UUID, Best>> board = leaderboard(c);
        if (board.isEmpty()) {
            text.append(Component.translatable("smartphone.parkour.board.empty").withStyle(ChatFormatting.GRAY));
        }
        for (int i = 0; i < Math.min(10, board.size()); i++) {
            Best b = board.get(i).getValue();
            ChatFormatting color = i == 0 ? ChatFormatting.GOLD : i == 1 ? ChatFormatting.WHITE : i == 2 ? ChatFormatting.RED : ChatFormatting.GRAY;
            text.append(Component.literal((i == 0 ? "" : "\n") + (i + 1) + ". ").withStyle(color))
                    .append(Component.literal(b.name + "  ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(time(b.ms)).withStyle(ChatFormatting.AQUA));
        }
        return text;
    }

    static void placeHologram(ServerPlayer p, Course c) {
        removeHologram(c);
        ServerLevel level = p.level();
        Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) return;
        display.snapTo(p.getX(), p.getEyeY() + 0.3, p.getZ(), 0, 0);
        display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        display.setLineWidth(220);
        display.setBackgroundColor(0x90000000);
        display.setText(boardText(c));
        level.addFreshEntity(display);
        c.hologram = display.getUUID().toString();
        c.hologramDim = level.dimension().identifier().toString();
        saveCourse(c);
    }

    static void removeHologram(Course c) {
        Entity e = hologram(c);
        if (e != null) e.discard();
        c.hologram = null;
        c.hologramDim = null;
    }

    private static Entity hologram(Course c) {
        if (c.hologram == null || c.hologramDim == null) return null;
        ServerLevel level = level(c.hologramDim);
        UUID id = PhoneDatabase.uuid(c.hologram);
        return level == null || id == null ? null : level.getEntity(id);
    }

    static void refreshHologram(Course c) {
        if (hologram(c) instanceof Display.TextDisplay display) display.setText(boardText(c));
    }

    // ------------------------------------------------------------------ phone

    public static void sendCourses(ServerPlayer p) {
        UUID me = p.getUUID();
        List<S2C.ParkourCourse> out = new ArrayList<>();
        for (Course c : COURSES.values()) {
            if (!c.playable()) continue;
            List<Map.Entry<UUID, Best>> board = leaderboard(c);
            Best mine = best(c, me);
            BlockPos s = c.start.pos();
            out.add(new S2C.ParkourCourse(c.name, c.display, c.checkpoints.size(), s.getX(), s.getY(), s.getZ(), c.dimension,
                    mine == null ? -1 : mine.ms, board.isEmpty() ? "" : board.get(0).getValue().name,
                    board.isEmpty() ? -1 : board.get(0).getValue().ms, rank(c, me), board.size()));
        }
        Invite inv = INVITES.get(me);
        Course ic = inv == null ? null : course(inv.course());
        ServerPlayNetworking.send(p, new S2C.ParkourCourses(out, inv == null ? "" : inv.fromName(), ic == null ? "" : ic.display));
    }

    public static void sendBoard(ServerPlayer p, String name) {
        Course c = course(name);
        if (c == null) return;
        List<S2C.ParkourBoardEntry> entries = new ArrayList<>();
        List<Map.Entry<UUID, Best>> board = leaderboard(c);
        for (int i = 0; i < Math.min(25, board.size()); i++) {
            Best b = board.get(i).getValue();
            entries.add(new S2C.ParkourBoardEntry(i + 1, b.name, b.ms, b.wins, board.get(i).getKey().equals(p.getUUID())));
        }
        Best mine = best(c, p.getUUID());
        ServerPlayNetworking.send(p, new S2C.ParkourBoard(c.name, c.display, entries, rank(c, p.getUUID()),
                mine == null ? -1 : mine.ms, mine == null ? 0 : mine.runs, mine == null ? 0 : mine.wins));
    }

    public static void challenge(ServerPlayer from, String course, String targetName) {
        ServerPlayer target = server == null ? null : server.getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            ServerPlayNetworking.send(from, new S2C.Notify("parkour", Component.translatable("smartphone.app.parkour"),
                    Component.translatable("smartphone.parkour.race.not_online", targetName)));
            return;
        }
        invite(from, course, target);
        ServerPlayNetworking.send(from, new S2C.Notify("parkour", Component.translatable("smartphone.app.parkour"),
                Component.translatable("smartphone.parkour.race.sent", target.getName().getString(), course(course) == null ? course : course(course).display)));
    }
}
