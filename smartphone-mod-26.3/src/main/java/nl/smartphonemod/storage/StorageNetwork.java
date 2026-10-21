package nl.smartphonemod.storage;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenCustomHashMap;
import it.unimi.dsi.fastutil.Hash;
import nl.smartphonemod.SmartphoneMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and changes the containers linked to a hub. Everything runs on the server thread, so a scan,
 * an extraction or an insertion can never interleave with hoppers or other players.
 */
public final class StorageNetwork {
    private StorageNetwork() {}

    /** Keeps linked chunks loaded (not ticking) for 5 seconds; refreshed while a screen is open. */
    public static final TicketType TICKET = net.minecraft.core.Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath(SmartphoneMod.MOD_ID, "storage"), new TicketType(100L, TicketType.FLAG_LOADING));

    public static void init() {}

    /** Item type + components; counts are ignored. */
    public static final Hash.Strategy<ItemStack> TYPE_AND_COMPONENTS = new Hash.Strategy<>() {
        @Override
        public int hashCode(ItemStack stack) {
            return ItemStack.hashItemAndComponents(stack);
        }

        @Override
        public boolean equals(ItemStack a, ItemStack b) {
            return a == b || a != null && b != null && a.isEmpty() == b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
        }
    };

    /** Containers a hub may link: chests (incl. trapped and copper), barrels and shulker boxes. Not hoppers. */
    public static boolean isStorage(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity;
    }

    /** The containers of a hub that can be used right now. */
    public record Resolved(List<Container> containers, int linked, int unavailable) {}

    /**
     * Looks up every linked position. Links whose block is no longer a storage container are removed;
     * links in unloaded chunks are counted as unavailable and (if {@code load}) get a ticket so they load.
     */
    public static Resolved resolve(ServerLevel level, StorageHubBlockEntity hub, boolean load) {
        List<Container> out = new ArrayList<>();
        LongArrayList gone = new LongArrayList();
        int unavailable = 0;
        long lastChunk = Long.MIN_VALUE;
        LevelChunk chunk = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long packed : hub.links()) {
            pos.set(packed);
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            long chunkKey = ChunkPos.pack(cx, cz);
            if (chunkKey != lastChunk) {
                lastChunk = chunkKey;
                chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (load) level.getChunkSource().addTicketWithRadius(TICKET, new ChunkPos(cx, cz), 0);
            }
            if (chunk == null) {
                unavailable++;
                continue;
            }
            BlockEntity be = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
            if (isStorage(be) && be instanceof Container container && !be.isRemoved()) out.add(container);
            else gone.add(packed);
        }
        if (!gone.isEmpty()) hub.unlinkAll(gone);
        return new Resolved(out, hub.links().size(), unavailable);
    }

    /** Totals per item type, plus slot statistics. */
    public record Snapshot(Object2LongLinkedOpenCustomHashMap<ItemStack> totals, int usedSlots, int totalSlots) {}

    public static Snapshot scan(List<Container> containers) {
        var totals = new Object2LongLinkedOpenCustomHashMap<ItemStack>(TYPE_AND_COMPONENTS);
        int used = 0, total = 0;
        for (Container c : containers) {
            int size = c.getContainerSize();
            total += size;
            for (int i = 0; i < size; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                used++;
                // The key must not change later, so store a count-1 copy the first time a type is seen.
                long prev = totals.getLong(s);
                if (prev == 0 && !totals.containsKey(s)) totals.put(s.copyWithCount(1), s.getCount());
                else totals.put(s, prev + s.getCount());
            }
        }
        return new Snapshot(totals, used, total);
    }

    /** Takes up to {@code amount} items matching {@code proto} out of the containers. */
    public static ItemStack extract(List<Container> containers, ItemStack proto, int amount) {
        if (proto.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack result = ItemStack.EMPTY;
        // Take from the fullest containers last: walk backwards so the front of a sorter row empties last.
        for (int ci = containers.size() - 1; ci >= 0 && amount > 0; ci--) {
            Container c = containers.get(ci);
            boolean changed = false;
            for (int i = c.getContainerSize() - 1; i >= 0 && amount > 0; i--) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, proto)) continue;
                int take = Math.min(amount, s.getCount());
                if (result.isEmpty()) result = s.copyWithCount(take);
                else result.grow(take);
                s.shrink(take);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                amount -= take;
                changed = true;
            }
            if (changed) c.setChanged();
        }
        return result;
    }

    /**
     * Puts {@code stack} into the network and returns what did not fit. Order: top up existing stacks,
     * then empty slots in containers that already hold this item (keeps an auto-sorter tidy), then any empty slot.
     */
    public static ItemStack insert(List<Container> containers, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack rest = stack.copy();
        List<Container> holding = new ArrayList<>();
        // 1) existing partial stacks
        for (Container c : containers) {
            boolean has = false, changed = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, rest)) continue;
                has = true;
                if (rest.isEmpty()) break;
                int max = Math.min(s.getMaxStackSize(), c.getMaxStackSize(s));
                int move = Math.min(rest.getCount(), max - s.getCount());
                if (move > 0) {
                    s.grow(move);
                    rest.shrink(move);
                    changed = true;
                }
            }
            if (changed) c.setChanged();
            if (has) holding.add(c);
            if (rest.isEmpty()) return ItemStack.EMPTY;
        }
        // 2) empty slots next to the same item, 3) any empty slot
        rest = fillEmpty(holding, rest);
        if (!rest.isEmpty()) rest = fillEmpty(containers, rest);
        return rest;
    }

    private static ItemStack fillEmpty(List<Container> containers, ItemStack rest) {
        for (Container c : containers) {
            boolean changed = false;
            for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
                if (!c.getItem(i).isEmpty() || !c.canPlaceItem(i, rest)) continue;
                int max = Math.min(rest.getMaxStackSize(), c.getMaxStackSize(rest));
                c.setItem(i, rest.split(Math.min(max, rest.getCount())));
                changed = true;
            }
            if (changed) c.setChanged();
            if (rest.isEmpty()) return ItemStack.EMPTY;
        }
        return rest;
    }
}
