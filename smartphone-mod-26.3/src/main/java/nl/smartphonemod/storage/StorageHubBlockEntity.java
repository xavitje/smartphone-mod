package nl.smartphonemod.storage;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import nl.smartphonemod.registry.ModBlockEntities;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;
import java.util.stream.LongStream;

/**
 * The storage hub: remembers which containers are linked to it (as packed block positions in its own
 * dimension), who placed it and its name. The items themselves always stay in the linked containers.
 */
public class StorageHubBlockEntity extends BlockEntity implements ExtendedMenuProvider<StorageMenu.OpenData> {
    private final LongLinkedOpenHashSet links = new LongLinkedOpenHashSet();
    private UUID owner;
    private String ownerName = "";
    private String name = "";

    /** Set just before openMenu() for a remote (phone) session. */
    private transient boolean openingRemote;

    public StorageHubBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STORAGE_HUB, pos, state);
    }

    // ------------------------------------------------------------------ links

    public LongLinkedOpenHashSet links() {
        return links;
    }

    public boolean isLinked(BlockPos pos) {
        return links.contains(pos.asLong());
    }

    public boolean link(BlockPos pos) {
        boolean added = links.add(pos.asLong());
        if (added) setChanged();
        return added;
    }

    public boolean unlink(BlockPos pos) {
        boolean removed = links.remove(pos.asLong());
        if (removed) setChanged();
        return removed;
    }

    public void unlinkAll(java.util.Collection<Long> positions) {
        boolean changed = false;
        for (long p : positions) changed |= links.remove(p);
        if (changed) setChanged();
    }

    // ------------------------------------------------------------------ owner & name

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public void setOwner(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName == null ? "" : ownerName;
        setChanged();
    }

    /** The name shown in the screen and the phone; falls back to the default. */
    public String displayName() {
        return name.isBlank() ? Component.translatable("block.smartphone.storage_hub").getString() : name;
    }

    public String rawName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.strip();
        if (this.name.length() > 32) this.name = this.name.substring(0, 32);
        setChanged();
    }

    // ------------------------------------------------------------------ menu

    /** Opens the storage screen for a player who is not standing at the hub (phone). */
    public void openRemote(ServerPlayer player) {
        openingRemote = true;
        try {
            player.openMenu(this);
        } finally {
            openingRemote = false;
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(displayName());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        return new StorageMenu(containerId, inventory, getScreenOpeningData((ServerPlayer) player), serverLevel);
    }

    @Override
    public StorageMenu.OpenData getScreenOpeningData(ServerPlayer player) {
        return new StorageMenu.OpenData(worldPosition, openingRemote, displayName());
    }

    // ------------------------------------------------------------------ save / load

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("Links", Codec.LONG_STREAM, LongStream.of(links.toLongArray()));
        if (owner != null) output.store("Owner", UUIDUtil.CODEC, owner);
        output.putString("OwnerName", ownerName);
        output.putString("HubName", name);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        links.clear();
        input.read("Links", Codec.LONG_STREAM).ifPresent(s -> s.forEach(links::add));
        owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("OwnerName", "");
        name = input.getStringOr("HubName", "");
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) StorageService.hubRemoved(serverLevel, pos);
    }
}
