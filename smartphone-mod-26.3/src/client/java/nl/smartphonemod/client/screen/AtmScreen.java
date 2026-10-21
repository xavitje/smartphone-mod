package nl.smartphonemod.client.screen;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.screen.AtmMenu;
import nl.smartphonemod.screen.CurrencyEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * ATM interface. Left: withdraw buttons per currency (1 / 16 / 64). Right: the deposit slot.
 * Buttons send vanilla menu-button packets; the server decides whether anything happens.
 */
public class AtmScreen extends AbstractContainerScreen<AtmMenu> {
    private static final int MAX_ROWS = 3;
    private static final int TEXT = 0xFF404040;
    /** Cash slot (where withdrawn items come out), relative to the panel. */
    private static final int SLOT_X = 118, SLOT_Y = 78, SLOT_W = 48;

    // ---- animation state
    private record Anim(ItemStack stack, long start, int kind, int index) {} // kind 0 = deposit, 1 = dispense
    private record Floater(String text, int color, long start) {}
    private final java.util.List<Anim> anims = new java.util.ArrayList<>();
    private final java.util.List<Floater> floaters = new java.util.ArrayList<>();
    private double shownBalance = -1, countFrom;
    private long countTo = Long.MIN_VALUE, countStart;
    private long knownBalance = -1;
    private ItemStack lastDeposit = ItemStack.EMPTY;
    private ItemStack lastWithdraw = ItemStack.EMPTY;
    private long lastWithdrawAt;
    private long openedAt = System.currentTimeMillis();

    public AtmScreen(AtmMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 196);
        this.inventoryLabelY = AtmMenu.INV_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        var currencies = menu.currencies();
        for (int i = 0; i < Math.min(MAX_ROWS, currencies.size()); i++) {
            CurrencyEntry c = currencies.get(i);
            int rowY = topPos + 32 + i * 22;
            for (int k = 0; k < AtmMenu.WITHDRAW_AMOUNTS.length; k++) {
                int buttonId = i * AtmMenu.WITHDRAW_AMOUNTS.length + k;
                int amount = Math.min(AtmMenu.WITHDRAW_AMOUNTS[k], new ItemStack(c.item()).getMaxStackSize());
                addRenderableWidget(Button.builder(Component.literal(Integer.toString(amount)),
                                b -> {
                                    lastWithdraw = new ItemStack(c.item(), amount);
                                    lastWithdrawAt = System.currentTimeMillis();
                                    beep(nl.smartphonemod.registry.ModSounds.ATM_BEEP, 1.0f);
                                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
                                })
                        .bounds(leftPos + 28 + k * 23, rowY, 22, 16)
                        .tooltip(Tooltip.create(Component.translatable("smartphone.atm.withdraw_tooltip",
                                amount, c.item().getName(new ItemStack(c.item())), amount * c.value())))
                        .build());
            }
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        // Vanilla-style panel drawn without a texture.
        int x0 = leftPos, y0 = topPos, x1 = leftPos + imageWidth, y1 = topPos + imageHeight;
        g.fill(x0, y0, x1, y1, 0xFF000000);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFFC6C6C6);
        g.fill(x0 + 1, y0 + 1, x1 - 2, y0 + 3, 0xFFFFFFFF);
        g.fill(x0 + 1, y0 + 1, x0 + 3, y1 - 2, 0xFFFFFFFF);
        g.fill(x0 + 3, y1 - 3, x1 - 1, y1 - 1, 0xFF555555);
        g.fill(x1 - 3, y0 + 3, x1 - 1, y1 - 1, 0xFF555555);

        // Display with the balance (counts up/down to the new value).
        g.fill(leftPos + 7, topPos + 16, leftPos + 169, topPos + 28, 0xFF1D2B22);
        long now = System.currentTimeMillis();
        if (now - openedAt < 600) {
            String welcome = Component.translatable("smartphone.atm.welcome").getString();
            int chars = (int) Math.min(welcome.length(), (now - openedAt) / 25);
            g.text(font, welcome.substring(0, chars), leftPos + 10, topPos + 18, 0xFF7CFC9A, false);
        } else {
            g.text(font, Component.translatable("smartphone.atm.balance", Math.round(shownBalance)),
                    leftPos + 10, topPos + 18, 0xFF7CFC9A, false);
        }
        // Scanline shimmer over the display.
        int scan = (int) ((now / 30) % 40);
        if (scan < 12) g.fill(leftPos + 7, topPos + 16 + scan, leftPos + 169, topPos + 17 + scan, 0x1A7CFC9A);

        // Cash slot
        int sx = leftPos + SLOT_X, sy = topPos + SLOT_Y;
        g.fill(sx - 1, sy - 1, sx + SLOT_W + 1, sy + 5, 0xFF555555);
        g.fill(sx, sy, sx + SLOT_W, sy + 4, 0xFF111111);
        boolean busy = anims.stream().anyMatch(an -> an.kind() == 1);
        g.fill(sx + SLOT_W + 3, sy, sx + SLOT_W + 6, sy + 4, busy && (now / 120) % 2 == 0 ? 0xFF4CFF6A : 0xFF2A4A30);

        for (Slot slot : menu.slots) drawSlot(g, leftPos + slot.x - 1, topPos + slot.y - 1);

        var currencies = menu.currencies();
        for (int i = 0; i < Math.min(MAX_ROWS, currencies.size()); i++) {
            g.item(new ItemStack(currencies.get(i).item()), leftPos + 8, topPos + 32 + i * 22);
        }
    }

    private void beep(net.minecraft.sounds.SoundEvent sound, float pitch) {
        minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, pitch, 0.6f));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        Slot deposit = menu.slots.get(0);
        if (deposit.hasItem()) lastDeposit = deposit.getItem().copy();
        long balance = ClientPhoneState.balance;
        if (knownBalance < 0 || System.currentTimeMillis() - openedAt < 800) { // first update is just the sync
            knownBalance = balance;
            shownBalance = balance;
            return;
        }
        if (balance != knownBalance) {
            long diff = balance - knownBalance;
            long now = System.currentTimeMillis();
            if (diff > 0) {
                ItemStack fly = lastDeposit.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.DIAMOND) : lastDeposit;
                anims.add(new Anim(fly, now, 0, 0));
                floaters.add(new Floater("+" + diff, 0xFF4CFF6A, now));
                beep(nl.smartphonemod.registry.ModSounds.ATM_COIN, 1.0f);
                lastDeposit = ItemStack.EMPTY;
            } else {
                ItemStack out = now - lastWithdrawAt < 3000 && !lastWithdraw.isEmpty() ? lastWithdraw
                        : new ItemStack(net.minecraft.world.item.Items.DIAMOND);
                int pieces = Math.max(1, Math.min(5, (out.getCount() + 7) / 8));
                for (int i = 0; i < pieces; i++) anims.add(new Anim(out.copyWithCount(1), now + i * 90L, 1, i));
                floaters.add(new Floater(Long.toString(diff), 0xFFFF6A5A, now));
                beep(nl.smartphonemod.registry.ModSounds.ATM_CASH, 1.0f);
            }
            knownBalance = balance;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        // Ease the shown balance towards the real one (counting animation).
        long target = ClientPhoneState.balance;
        if (shownBalance < 0) shownBalance = target;
        if (target != countTo) { // start a new count of ~0.7 s from where the display is now
            countFrom = shownBalance;
            countTo = target;
            countStart = System.currentTimeMillis();
        }
        float ct = Math.min(1, (System.currentTimeMillis() - countStart) / 700f);
        shownBalance = countFrom + (countTo - countFrom) * (1 - (1 - ct) * (1 - ct));
        super.extractRenderState(g, mouseX, mouseY, a);
        long now = System.currentTimeMillis();
        anims.removeIf(an -> now - an.start() > 900);
        for (Anim an : anims) {
            float t = (now - an.start()) / (an.kind() == 0 ? 450f : 900f);
            if (t < 0) continue;
            t = Math.min(1, t);
            float x, y, scale;
            if (an.kind() == 0) {
                // From the deposit slot up into the display, shrinking.
                float e = 1 - (1 - t) * (1 - t);
                x = leftPos + AtmMenu.DEPOSIT_X + (88 - 8 - AtmMenu.DEPOSIT_X) * e;
                y = topPos + AtmMenu.DEPOSIT_Y + (18 - AtmMenu.DEPOSIT_Y) * e;
                scale = 1 - 0.7f * e;
                if (t >= 1) continue;
            } else {
                // Out of the cash slot, then down into the inventory.
                float slide = Math.min(1, t / 0.35f);
                float drop = Math.max(0, (t - 0.35f) / 0.65f);
                x = leftPos + SLOT_X + 8 + an.index() * 7;
                y = topPos + SLOT_Y - 10 + 14 * slide + drop * drop * 70;
                scale = 1;
            }
            g.pose().pushMatrix();
            g.pose().translate(x + 8, y + 8);
            g.pose().scale(scale, scale);
            g.item(an.stack(), -8, -8);
            g.pose().popMatrix();
        }
        floaters.removeIf(f -> now - f.start() > 1200);
        for (Floater f : floaters) {
            float t = (now - f.start()) / 1200f;
            int alpha = Math.max(8, (int) (255 * Math.min(1, (1 - t) * 2)));
            String s = f.text() + " \u25C6";
            g.text(font, s, leftPos + 166 - font.width(s), (int) (topPos + 18 - t * 3),
                    (alpha << 24) | (f.color() & 0xFFFFFF), false);
        }
    }

    private static void drawSlot(GuiGraphicsExtractor g, int sx, int sy) {
        g.fill(sx, sy, sx + 18, sy + 18, 0xFF8B8B8B);
        g.fill(sx, sy, sx + 17, sy + 1, 0xFF373737);
        g.fill(sx, sy, sx + 1, sy + 17, 0xFF373737);
        g.fill(sx + 1, sy + 17, sx + 18, sy + 18, 0xFFFFFFFF);
        g.fill(sx + 17, sy + 1, sx + 18, sy + 18, 0xFFFFFFFF);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        Component deposit = Component.translatable("smartphone.atm.deposit");
        g.text(font, deposit, AtmMenu.DEPOSIT_X + 8 - font.width(deposit) / 2, AtmMenu.DEPOSIT_Y - 11, TEXT, false);

        if (ClientPhoneState.statusTime > 0 && System.currentTimeMillis() - ClientPhoneState.statusTime < 4000
                && !ClientPhoneState.statusKey.isEmpty()) {
            Object[] args = ClientPhoneState.statusArg.isEmpty() ? new Object[0] : ClientPhoneState.statusArg.split("\\|");
            Component status = Component.translatable(ClientPhoneState.statusKey, args);
            g.text(font, status, imageWidth - 8 - font.width(status), titleLabelY,
                    ClientPhoneState.statusOk ? 0xFF1F7A35 : 0xFFB3261E, false);
        }
    }
}
