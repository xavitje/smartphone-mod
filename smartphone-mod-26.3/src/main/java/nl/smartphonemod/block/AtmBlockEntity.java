package nl.smartphonemod.block;

import nl.smartphonemod.registry.ModBlockEntities;
import nl.smartphonemod.screen.AtmMenu;
import nl.smartphonemod.screen.CurrencyEntry;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

/** Holds the ATM's single deposit slot. Items never stay in it for more than a tick. */
public class AtmBlockEntity extends BlockEntity implements ExtendedMenuProvider<List<CurrencyEntry>> {
    private final SimpleContainer container = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            AtmBlockEntity.this.setChanged();
        }
    };

    public AtmBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ATM, pos, state);
    }

    public SimpleContainer getContainer() {
        return container;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.smartphone.atm");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new AtmMenu(containerId, inventory, CurrencyEntry.fromConfig(), container,
                ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    public List<CurrencyEntry> getScreenOpeningData(ServerPlayer player) {
        return CurrencyEntry.fromConfig();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, container.getItems());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        container.getItems().clear();
        ContainerHelper.loadAllItems(input, container.getItems());
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null) net.minecraft.world.Containers.dropContents(level, pos, container);
    }
}
