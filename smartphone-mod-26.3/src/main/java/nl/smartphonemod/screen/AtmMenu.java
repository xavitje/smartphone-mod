package nl.smartphonemod.screen;

import nl.smartphonemod.registry.ModBlocks;
import nl.smartphonemod.registry.ModMenus;
import nl.smartphonemod.server.BankService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * ATM menu: one deposit slot plus the player's inventory.
 *
 * <p>Deposits: anything accepted that lands in the slot is destroyed by the server on the next
 * tick and credited to the player who put it there. Withdrawals use vanilla menu-button clicks
 * (id = currencyIndex * 3 + amountIndex), so no extra packet is needed and the server checks everything.
 */
public class AtmMenu extends AbstractContainerMenu {
    public static final int[] WITHDRAW_AMOUNTS = {1, 16, 64};

    public static final int DEPOSIT_X = 134, DEPOSIT_Y = 44;
    public static final int INV_Y = 114;

    private final List<CurrencyEntry> currencies;
    private final Container deposit;
    private final ContainerLevelAccess access;
    private final Player player;

    /** Client constructor: the currency table arrives from the server. */
    public AtmMenu(int containerId, Inventory inventory, List<CurrencyEntry> currencies) {
        this(containerId, inventory, currencies, new SimpleContainer(1), ContainerLevelAccess.NULL);
    }

    public AtmMenu(int containerId, Inventory inventory, List<CurrencyEntry> currencies,
                   Container deposit, ContainerLevelAccess access) {
        super(ModMenus.ATM, containerId);
        checkContainerSize(deposit, 1);
        this.currencies = List.copyOf(currencies);
        this.deposit = deposit;
        this.access = access;
        this.player = inventory.player;
        deposit.startOpen(player);

        addSlot(new Slot(deposit, 0, DEPOSIT_X, DEPOSIT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return CurrencyEntry.valueOf(AtmMenu.this.currencies, stack.getItem()) > 0;
            }
        });
        addStandardInventorySlots(inventory, 8, INV_Y);
    }

    public List<CurrencyEntry> currencies() {
        return currencies;
    }

    /** Runs every tick on the server while the menu is open. */
    @Override
    public void broadcastChanges() {
        digitise();
        super.broadcastChanges();
    }

    /** Destroys whatever valuable items sit in the slot and credits their value. */
    private void digitise() {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        ItemStack stack = deposit.getItem(0);
        if (stack.isEmpty()) return;
        long unit = CurrencyEntry.valueOf(currencies, stack.getItem());
        ItemStack taken = deposit.removeItemNoUpdate(0); // items leave the world first...
        deposit.setChanged();
        if (unit <= 0) {
            player.getInventory().placeItemBackInInventory(taken, net.minecraft.util.Prediction.SERVER_ONLY);
            return;
        }
        long total;
        try {
            total = Math.multiplyExact(unit, (long) taken.getCount());
        } catch (ArithmeticException overflow) {
            player.getInventory().placeItemBackInInventory(taken, net.minecraft.util.Prediction.SERVER_ONLY);
            return;
        }
        BankService.deposit(serverPlayer, total, taken); // ...then the balance is credited (refunded on failure)
    }

    @Override
    public boolean clickMenuButton(Player clicker, int id) {
        if (!(clicker instanceof ServerPlayer serverPlayer)) return true;
        if (id < 0) return false;
        int index = id / WITHDRAW_AMOUNTS.length;
        int amountIndex = id % WITHDRAW_AMOUNTS.length;
        if (index >= currencies.size()) return false;

        CurrencyEntry currency = currencies.get(index);
        int count = Math.min(WITHDRAW_AMOUNTS[amountIndex], new ItemStack(currency.item()).getMaxStackSize());
        long cost;
        try {
            cost = Math.multiplyExact(currency.value(), (long) count);
        } catch (ArithmeticException overflow) {
            return false;
        }
        BankService.withdraw(serverPlayer, new ItemStack(currency.item(), count), cost);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (slotIndex == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!slots.get(0).mayPlace(stack) || !moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.ATM);
    }

    @Override
    public void removed(Player player) {
        // Anything still in the slot is digitised (credited) right away rather than left behind.
        if (player instanceof ServerPlayer) digitise();
        super.removed(player);
        deposit.stopOpen(player);
    }
}
