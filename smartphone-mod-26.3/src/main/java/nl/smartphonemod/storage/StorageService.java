package nl.smartphonemod.storage;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.config.SmartphoneConfig;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.registry.ModItems;
import nl.smartphonemod.server.PhoneDatabase;
import nl.smartphonemod.server.PhoneServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server logic of the storage network: the linker tool, access checks, opening hubs at the block or
 * through the phone, the hub registry (for the phone's list) and the link particles.
 */
public final class StorageService {
    private StorageService() {}

    public static void register() {
        StorageNetwork.init();
        UseBlockCallback.EVENT.register(StorageService::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(StorageService::tick);
    }

    private static SmartphoneConfig cfg() {
        return SmartphoneConfig.get();
    }

    private static void say(Player player, String key, ChatFormatting color, Object... args) {
        player.sendOverlayMessage(Component.translatable(key, args).withStyle(color));
    }

    // ------------------------------------------------------------------ registry

    static void hubPlaced(ServerLevel level, StorageHubBlockEntity hub) {
        upsert(level, hub);
    }

    private static void upsert(ServerLevel level, StorageHubBlockEntity hub) {
        if (!PhoneServer.isRunning() || hub.owner() == null) return;
        String dim = level.dimension().identifier().toString();
        BlockPos p = hub.getBlockPos();
        String owner = hub.owner().toString();
        String name = hub.rawName();
        PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO storage_hubs(dim, x, y, z, owner, name) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, dim);
                ps.setInt(2, p.getX());
                ps.setInt(3, p.getY());
                ps.setInt(4, p.getZ());
                ps.setString(5, owner);
                ps.setString(6, name);
                return ps.executeUpdate();
            }
        });
    }

    static void hubRemoved(ServerLevel level, BlockPos p) {
        if (!PhoneServer.isRunning()) return;
        String dim = level.dimension().identifier().toString();
        PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM storage_hubs WHERE dim = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, dim);
                ps.setInt(2, p.getX());
                ps.setInt(3, p.getY());
                ps.setInt(4, p.getZ());
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ access

    private static boolean isAdmin(ServerPlayer p) {
        return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** Owner, the owner's friends (Settings → friends) and admins. Runs {@code then} on the server thread. */
    private static void checkAccess(ServerPlayer player, UUID owner, Consumer<Boolean> then) {
        if (owner == null || owner.equals(player.getUUID()) || isAdmin(player)) {
            then.accept(true);
            return;
        }
        if (!PhoneServer.isRunning()) {
            then.accept(false);
            return;
        }
        String o = owner.toString(), me = player.getUUID().toString();
        PhoneServer.then(PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM friends WHERE owner = ? AND friend = ?")) {
                ps.setString(1, o);
                ps.setString(2, me);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }), player, (p, ok) -> then.accept(ok));
    }

    // ------------------------------------------------------------------ opening

    static void openAtBlock(ServerPlayer player, StorageHubBlockEntity hub) {
        if (hub.owner() == null) { // placed by a command or world edit: the first user claims it
            hub.setOwner(player.getUUID(), player.getName().getString());
        }
        if (hub.getLevel() instanceof ServerLevel level) upsert(level, hub);
        if (!cfg().storageHubPrivate) {
            player.openMenu(hub);
            return;
        }
        checkAccess(player, hub.owner(), ok -> {
            if (!ok) {
                say(player, "smartphone.storage.no_access", ChatFormatting.RED, hub.ownerName());
                return;
            }
            if (!hub.isRemoved() && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(hub.getBlockPos())) <= 64.0) player.openMenu(hub);
        });
    }

    /** Phone → Storage app → tap a hub. */
    public static void openRemote(ServerPlayer player, String dimension, BlockPos pos) {
        if (!cfg().storageRemoteAccess) {
            notifyFail(player, "smartphone.storage.remote_off");
            return;
        }
        MinecraftServer server = PhoneServer.server();
        Identifier dimId = Identifier.tryParse(dimension);
        ServerLevel level = server == null || dimId == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (level == null || !level.isInWorldBounds(pos)) return;
        String me = player.getUUID().toString();
        boolean admin = isAdmin(player);
        PhoneServer.then(PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT h.owner, (h.owner = ?5 OR EXISTS (SELECT 1 FROM friends f WHERE f.owner = h.owner AND f.friend = ?5))
                    FROM storage_hubs h WHERE h.dim = ?1 AND h.x = ?2 AND h.y = ?3 AND h.z = ?4""")) {
                ps.setString(1, dimension);
                ps.setInt(2, pos.getX());
                ps.setInt(3, pos.getY());
                ps.setInt(4, pos.getZ());
                ps.setString(5, me);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBoolean(2) : null;
                }
            }
        }), player, (p, allowed) -> {
            if (allowed == null || !(allowed || admin)) {
                notifyFail(p, "smartphone.storage.no_access_remote");
                return;
            }
            // Load the hub's chunk (one chunk, briefly) and keep it loaded while the screen is open.
            level.getChunkSource().addTicketWithRadius(StorageNetwork.TICKET, ChunkPos.containing(pos), 0);
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (!(level.getBlockEntity(pos) instanceof StorageHubBlockEntity hub)) {
                hubRemoved(level, pos);
                notifyFail(p, "smartphone.storage.hub_gone");
                sendHubs(p);
                return;
            }
            long fee = cfg().storageRemoteFee;
            if (fee <= 0) {
                hub.openRemote(p);
                return;
            }
            UUID uuid = p.getUUID();
            PhoneServer.then(PhoneServer.db().transaction(tx -> {
                if (!PhoneDatabase.debit(tx, uuid, fee)) return false;
                PhoneDatabase.log(tx, "STORAGE", uuid, null, fee, 0, hub.displayName());
                return true;
            }), p, (p2, paid) -> {
                if (!paid) {
                    notifyFail(p2, "smartphone.storage.cannot_pay", fee);
                    return;
                }
                if (!hub.isRemoved()) hub.openRemote(p2);
            });
        });
    }

    private static void notifyFail(ServerPlayer player, String key, Object... args) {
        ServerPlayNetworking.send(player, new S2C.Notify("storage", Component.translatable("smartphone.app.storage"),
                Component.translatable(key, args)));
    }

    /** The hubs a player may open through the phone: their own and those of players who marked them as a friend. */
    public static void sendHubs(ServerPlayer player) {
        if (!PhoneServer.isRunning()) return;
        String me = player.getUUID().toString();
        PhoneServer.then(PhoneServer.db().submit(c -> {
            List<S2C.HubEntry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT h.dim, h.x, h.y, h.z, h.name, COALESCE(a.name, '?'), h.owner = ?1
                    FROM storage_hubs h LEFT JOIN accounts a ON a.uuid = h.owner
                    WHERE h.owner = ?1 OR EXISTS (SELECT 1 FROM friends f WHERE f.owner = h.owner AND f.friend = ?1)
                    ORDER BY (h.owner = ?1) DESC, h.name, h.x LIMIT 100""")) {
                ps.setString(1, me);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new S2C.HubEntry(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                                rs.getString(5), rs.getString(6), rs.getBoolean(7)));
                    }
                }
            }
            return out;
        }), player, (p, hubs) -> ServerPlayNetworking.send(p,
                new S2C.StorageHubs(hubs, cfg().storageRemoteAccess, cfg().storageRemoteFee)));
    }

    // ------------------------------------------------------------------ linker

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(ModItems.LINKER) || player.isSpectator()) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
            try {
                useLinker(sp, sl, stack, hit.getBlockPos());
            } catch (RuntimeException e) {
                SmartphoneMod.LOGGER.error("Linker failed", e);
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void useLinker(ServerPlayer player, ServerLevel level, ItemStack stack, BlockPos pos) {
        BlockEntity clicked = level.getBlockEntity(pos);
        if (clicked instanceof StorageHubBlockEntity hub) {
            if (hub.owner() == null) hub.setOwner(player.getUUID(), player.getName().getString());
            checkAccess(player, hub.owner(), ok -> {
                if (!ok) {
                    say(player, "smartphone.storage.no_access", ChatFormatting.RED, hub.ownerName());
                    return;
                }
                ItemStack held = player.getMainHandItem().is(ModItems.LINKER) ? player.getMainHandItem() : player.getOffhandItem();
                if (!held.is(ModItems.LINKER) || hub.isRemoved()) return;
                LinkerItem.select(held, hub, player);
                say(player, "smartphone.linker.selected", ChatFormatting.AQUA, hub.displayName(), hub.links().size());
                level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, 1.2f);
            });
            return;
        }

        LinkerItem.Selection sel = LinkerItem.selection(stack);
        if (sel == null || sel.by() == null || !sel.by().equals(player.getUUID())) {
            say(player, "smartphone.linker.select_first", ChatFormatting.YELLOW);
            return;
        }
        if (!sel.dimension().equals(level.dimension())) {
            say(player, "smartphone.linker.other_dimension", ChatFormatting.RED);
            return;
        }
        if (level.getChunkSource().getChunkNow(sel.hub().getX() >> 4, sel.hub().getZ() >> 4) == null
                || !(level.getBlockEntity(sel.hub()) instanceof StorageHubBlockEntity hub)) {
            say(player, "smartphone.linker.hub_missing", ChatFormatting.RED);
            return;
        }

        if (player.isShiftKeyDown()) {
            BlockPos first = LinkerItem.corner(stack, level.dimension());
            if (first == null) {
                LinkerItem.setCorner(stack, pos, level.dimension());
                say(player, "smartphone.linker.corner1", ChatFormatting.GOLD, pos.getX(), pos.getY(), pos.getZ());
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 0.6f, 1.6f);
            } else {
                LinkerItem.setCorner(stack, null, level.dimension());
                linkArea(player, level, hub, first, pos);
            }
            return;
        }

        if (!StorageNetwork.isStorage(clicked)) {
            say(player, "smartphone.linker.not_storage", ChatFormatting.YELLOW);
            return;
        }
        List<BlockPos> targets = withChestPartner(level, pos);
        boolean unlink = hub.isLinked(pos);
        if (unlink) {
            for (BlockPos t : targets) hub.unlink(t);
            say(player, "smartphone.linker.unlinked", ChatFormatting.GRAY, hub.links().size());
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.6f, 0.8f);
            return;
        }
        if (!inRange(hub, pos)) {
            say(player, "smartphone.linker.too_far", ChatFormatting.RED, cfg().storageMaxRange);
            return;
        }
        for (BlockPos t : targets) {
            if (hub.links().size() >= cfg().storageMaxLinks) {
                say(player, "smartphone.linker.full", ChatFormatting.RED, cfg().storageMaxLinks);
                return;
            }
            hub.link(t);
        }
        say(player, "smartphone.linker.linked", ChatFormatting.GREEN, hub.links().size());
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 0.6f, 1.5f);
    }

    private static boolean inRange(StorageHubBlockEntity hub, BlockPos pos) {
        double r = cfg().storageMaxRange;
        return hub.getBlockPos().distSqr(pos) <= r * r;
    }

    /** A chest and, for a double chest, its other half (so both halves are always linked together). */
    private static List<BlockPos> withChestPartner(ServerLevel level, BlockPos pos) {
        List<BlockPos> out = new ArrayList<>(2);
        out.add(pos);
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (StorageNetwork.isStorage(level.getBlockEntity(other))) out.add(other);
        }
        return out;
    }

    private static void linkArea(ServerPlayer player, ServerLevel level, StorageHubBlockEntity hub, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        int max = cfg().storageMaxAreaSize;
        if (maxX - minX + 1 > max || maxY - minY + 1 > max || maxZ - minZ + 1 > max) {
            say(player, "smartphone.linker.area_too_big", ChatFormatting.RED, max);
            return;
        }
        int added = 0, already = 0, tooFar = 0, unloaded = 0;
        boolean full = false;
        for (int cx = minX >> 4; cx <= maxX >> 4 && !full; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4 && !full; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    unloaded++;
                    continue;
                }
                // Sorted for a stable order (rows of chests stay together in the network).
                List<BlockPos> found = new ArrayList<>();
                for (var e : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = e.getKey();
                    if (p.getX() < minX || p.getX() > maxX || p.getY() < minY || p.getY() > maxY || p.getZ() < minZ || p.getZ() > maxZ) continue;
                    if (StorageNetwork.isStorage(e.getValue())) found.add(p.immutable());
                }
                found.sort(BlockPos::compareTo);
                for (BlockPos p : found) {
                    if (hub.isLinked(p)) {
                        already++;
                    } else if (!inRange(hub, p)) {
                        tooFar++;
                    } else if (hub.links().size() >= cfg().storageMaxLinks) {
                        full = true;
                        break;
                    } else {
                        hub.link(p);
                        added++;
                    }
                }
            }
        }
        say(player, "smartphone.linker.area_done", ChatFormatting.GREEN, added, already, hub.links().size());
        if (tooFar > 0) player.sendSystemMessage(Component.translatable("smartphone.linker.area_too_far", tooFar, cfg().storageMaxRange).withStyle(ChatFormatting.YELLOW));
        if (unloaded > 0) player.sendSystemMessage(Component.translatable("smartphone.linker.area_unloaded", unloaded).withStyle(ChatFormatting.YELLOW));
        if (full) player.sendSystemMessage(Component.translatable("smartphone.linker.full", cfg().storageMaxLinks).withStyle(ChatFormatting.RED));
        level.playSound(null, b, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.5f, 1.4f);
    }

    // ------------------------------------------------------------------ particles

    private static int tickCounter;

    /** While a player holds a linker: green sparkles on linked containers near them, a beam on the hub. */
    private static void tick(MinecraftServer server) {
        if (++tickCounter % 10 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack stack = player.getMainHandItem().is(ModItems.LINKER) ? player.getMainHandItem()
                    : player.getOffhandItem().is(ModItems.LINKER) ? player.getOffhandItem() : ItemStack.EMPTY;
            if (stack.isEmpty()) continue;
            ServerLevel level = player.level();
            BlockPos corner = LinkerItem.corner(stack, level.dimension());
            if (corner != null) particle(player, ParticleTypes.FLAME, corner, 6, 0.35f);
            LinkerItem.Selection sel = LinkerItem.selection(stack);
            if (sel == null || !sel.dimension().equals(level.dimension())) continue;
            if (level.getChunkSource().getChunkNow(sel.hub().getX() >> 4, sel.hub().getZ() >> 4) == null) continue;
            if (!(level.getBlockEntity(sel.hub()) instanceof StorageHubBlockEntity hub)) continue;
            if (player.blockPosition().distSqr(sel.hub()) < 64 * 64) particle(player, ParticleTypes.END_ROD, sel.hub(), 4, 0.4f);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            int sent = 0;
            BlockPos me = player.blockPosition();
            for (long packed : hub.links()) {
                p.set(packed);
                if (p.distSqr(me) > 24 * 24) continue;
                particle(player, ParticleTypes.HAPPY_VILLAGER, p, 2, 0.3f);
                if (++sent >= 250) break;
            }
        }
    }

    private static void particle(ServerPlayer player, ParticleOptions type, BlockPos pos, int count, float spread) {
        player.connection.send(new ClientboundLevelParticlesPacket(type, false, true,
                pos.getX() + 0.5, pos.getY() + 0.75, pos.getZ() + 0.5, spread, spread, spread, 0f, count));
    }
}
