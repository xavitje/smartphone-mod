package nl.smartphonemod.storage;

import nl.smartphonemod.network.S2C;
import nl.smartphonemod.registry.ModMenus;
import nl.smartphonemod.server.PhoneServer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The storage screen's menu: the player's inventory as normal slots, plus a server-side view of the
 * network. The network part is not made of slots: the client sends extract/insert requests and the
 * server answers with fresh contents. The cursor (carried) item uses vanilla's own syncing.
 */
public class StorageMenu extends AbstractContainerMenu {
    public static final int GRID_X = 8, GRID_Y = 30, COLS = 9, ROWS = 6, CELL = 18;
    public static final int INV_Y = 154;
    public static final int WIDTH = 194, HEIGHT = 236;
    /** Menu-button id: store the whole main inventory (not the hotbar) in the network. */
    public static final int BUTTON_STORE_INVENTORY = 0;

    /** Sent to the client when the screen opens. */
    public record OpenData(BlockPos hub, boolean remote, String name) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenData> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenData::hub,
                ByteBufCodecs.BOOL, OpenData::remote,
                ByteBufCodecs.stringUtf8(64), OpenData::name,
                OpenData::new);
    }

    private final OpenData data;
    private final Player player;

    // ---- client side: what the server last sent
    public List<S2C.StorageEntry> entries = List.of();
    public int linked, unavailable, usedSlots, totalSlots;
    public long contentsVersion;

    // ---- server side
    private final ServerLevel hubLevel;
    private int ticks;
    private boolean dirty = true;
    private long lastHash = Long.MIN_VALUE;

    /** Client constructor. */
    public StorageMenu(int containerId, Inventory inventory, OpenData data) {
        this(containerId, inventory, data, null);
    }

    public StorageMenu(int containerId, Inventory inventory, OpenData data, ServerLevel hubLevel) {
        super(ModMenus.STORAGE, containerId);
        this.data = data;
        this.player = inventory.player;
        this.hubLevel = hubLevel;
        addStandardInventorySlots(inventory, 8, INV_Y);
    }

    public OpenData data() {
        return data;
    }

    public boolean remote() {
        return data.remote();
    }

    // ------------------------------------------------------------------ server

    private StorageHubBlockEntity hub() {
        if (hubLevel == null) return null;
        BlockPos pos = data.hub();
        if (data.remote()) hubLevel.getChunkSource().addTicketWithRadius(StorageNetwork.TICKET, ChunkPos.containing(pos), 0);
        if (hubLevel.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return null;
        return hubLevel.getBlockEntity(pos) instanceof StorageHubBlockEntity hub ? hub : null;
    }

    private List<net.minecraft.world.Container> containers() {
        StorageHubBlockEntity hub = hub();
        return hub == null ? List.of() : StorageNetwork.resolve(hubLevel, hub, true).containers();
    }

    /** Runs every tick on the server while the menu is open. */
    @Override
    public void broadcastChanges() {
        if (player instanceof ServerPlayer serverPlayer && hubLevel != null) {
            if (dirty || ++ticks >= 20) {
                ticks = 0;
                dirty = false;
                sync(serverPlayer);
            }
        }
        super.broadcastChanges();
    }

    private void sync(ServerPlayer target) {
        StorageHubBlockEntity hub = hub();
        if (hub == null) {
            if (lastHash != 0) ServerPlayNetworking.send(target, new S2C.StorageContents(containerId, List.of(), 0, 0, 0, 0));
            lastHash = 0;
            return;
        }
        StorageNetwork.Resolved r = StorageNetwork.resolve(hubLevel, hub, true);
        StorageNetwork.Snapshot snap = StorageNetwork.scan(r.containers());
        long hash = 1469598103934665603L;
        hash = hash * 31 + r.linked();
        hash = hash * 31 + r.unavailable();
        hash = hash * 31 + snap.usedSlots();
        hash = hash * 31 + snap.totalSlots();
        List<S2C.StorageEntry> list = new ArrayList<>(snap.totals().size());
        for (var e : snap.totals().object2LongEntrySet()) {
            hash = hash * 1_000_003L + (ItemStack.hashItemAndComponents(e.getKey()) * 31L + e.getLongValue());
            list.add(new S2C.StorageEntry(e.getKey(), e.getLongValue()));
        }
        if (hash == lastHash) return;
        lastHash = hash;
        ServerPlayNetworking.send(target, new S2C.StorageContents(containerId, list, r.linked(), r.unavailable(),
                snap.usedSlots(), snap.totalSlots()));
    }

    /** Client asked for items: onto the cursor, or straight into the inventory. */
    public void extract(ServerPlayer p, ItemStack proto, int amount, boolean toInventory) {
        if (proto.isEmpty()) return;
        if (!toInventory && !getCarried().isEmpty()) return;
        amount = Math.max(1, Math.min(amount, proto.getMaxStackSize()));
        var containers = containers();
        ItemStack got = StorageNetwork.extract(containers, proto, amount);
        dirty = true;
        if (got.isEmpty()) return;
        if (toInventory) {
            p.getInventory().add(got);
            if (!got.isEmpty()) got = StorageNetwork.insert(containers, got); // inventory full: put the rest back
            if (!got.isEmpty()) p.drop(got, false, net.minecraft.util.Prediction.SERVER_ONLY);
        } else {
            setCarried(got);
        }
    }

    /** Client clicked the grid while holding something: store all of it, or one item. */
    public void insertCarried(boolean single) {
        ItemStack carried = getCarried();
        if (carried.isEmpty()) return;
        var containers = containers();
        if (single) {
            if (StorageNetwork.insert(containers, carried.copyWithCount(1)).isEmpty()) carried.shrink(1);
        } else {
            setCarried(StorageNetwork.insert(containers, carried));
        }
        dirty = true;
    }

    @Override
    public boolean clickMenuButton(Player clicker, int id) {
        if (id != BUTTON_STORE_INVENTORY || !(clicker instanceof ServerPlayer)) return false;
        var containers = containers();
        Inventory inv = clicker.getInventory();
        for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            inv.setItem(i, StorageNetwork.insert(containers, s));
        }
        dirty = true;
        return true;
    }

    /** Shift-click in the inventory stores that stack in the network. */
    @Override
    public ItemStack quickMoveStack(Player clicker, int slotIndex) {
        if (clicker.level().isClientSide() || slotIndex < 0 || slotIndex >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack rest = StorageNetwork.insert(containers(), slot.getItem());
        slot.setByPlayer(rest);
        dirty = true;
        return ItemStack.EMPTY; // one pass only; the client is resynced by broadcastChanges
    }

    @Override
    public boolean stillValid(Player p) {
        if (hubLevel == null) return true; // client
        if (data.remote()) {
            return p instanceof ServerPlayer sp && PhoneServer.isRunning() && PhoneServer.hasPhone(sp)
                    && nl.smartphonemod.config.SmartphoneConfig.get().storageRemoteAccess && hub() != null;
        }
        return p.level() == hubLevel && hub() != null && p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(data.hub())) <= 64.0;
    }
}
