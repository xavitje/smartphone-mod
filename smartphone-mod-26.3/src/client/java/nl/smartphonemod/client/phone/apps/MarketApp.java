package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.registry.ModItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Marktplaats: browse and buy, sell from your inventory, manage your own listings. */
public class MarketApp extends PhoneApp {
    private int tab;
    private int selectedSlot = -1;
    private boolean requested;

    @Override
    public String id() {
        return "market";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.app.market"), null);
        if (!requested) {
            requested = true;
            ClientPhoneState.marketStatusKey = "";
            ClientPlayNetworking.send(new C2S.MarketRequest(""));
        }
        long mine = ClientPhoneState.listings.stream().filter(S2C.ListingEntry::mine).count();
        col.addChild(new UiKit.Segmented(w, List.of(Component.translatable("smartphone.market.tab.buy"),
                Component.translatable("smartphone.market.tab.sell"),
                Component.translatable("smartphone.market.tab.mine").append(mine > 0 ? " (" + mine + ")" : "")), tab, i -> {
            tab = i;
            ClientPhoneState.marketStatusKey = "";
            phone.refresh();
        }));
        switch (tab) {
            case 1 -> sellTab(col);
            case 2 -> mineTab(col);
            default -> buyTab(col);
        }
    }

    private void status(LinearLayout col) {
        status(col, ClientPhoneState.marketStatusKey, ClientPhoneState.marketStatusArg, ClientPhoneState.marketStatusOk,
                ClientPhoneState.marketStatusTime);
    }

    private static UiKit.ListRow.Leading itemIcon(ItemStack stack) {
        return (g, x, y, size) -> {
            UiKit.roundRect(g, x, y, size, size, 5, UiKit.SURFACE_2);
            g.item(stack, x + (size - 16) / 2, y + (size - 16) / 2);
            g.itemDecorations(net.minecraft.client.Minecraft.getInstance().font, stack, x + (size - 16) / 2, y + (size - 16) / 2);
        };
    }

    private void buyTab(LinearLayout col) {
        LinearLayout search = LinearLayout.horizontal().spacing(4);
        search.addChild(field("q", w - 4 - 40, Component.translatable("smartphone.market.search"), 32, this::search));
        search.addChild(new UiKit.Button(40, 18, Component.translatable("smartphone.market.find"), UiKit.Style.GREY, UiKit.BLUE, this::search));
        col.addChild(search);
        status(col);
        UiKit.ScrollList list = list("buy", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        List<S2C.ListingEntry> others = ClientPhoneState.listings.stream().filter(l -> !l.mine()).toList();
        if (others.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.market.empty"), UiKit.TEXT_3));
        for (S2C.ListingEntry l : others) {
            boolean affordable = ClientPhoneState.balance >= l.price();
            rows.add(new UiKit.ListRow(itemIcon(l.item()), l.item().getHoverName(),
                    Component.translatable("smartphone.market.by", l.seller()), Component.literal(l.price() + " ◆"),
                    affordable ? UiKit.TEXT : UiKit.RED, null)
                    .button(Component.translatable("smartphone.market.buy"), affordable ? UiKit.GREEN : UiKit.TEXT_3,
                            () -> ClientPlayNetworking.send(new C2S.MarketAction(l.id(), true)))
                    .tooltip(Component.literal(l.item().getCount() + "x ").append(l.item().getHoverName())));
        }
        list.rows(rows);
    }

    private void search() {
        ClientPlayNetworking.send(new C2S.MarketRequest(text("q")));
    }

    private void sellTab(LinearLayout col) {
        col.addChild(new UiKit.Label(Component.translatable("smartphone.market.pick_item"), UiKit.TEXT_2, w));
        col.addChild(new InventoryGrid(w));
        ItemStack selected = selectedSlot >= 0 && mc.player != null ? mc.player.getInventory().getItem(selectedSlot) : ItemStack.EMPTY;
        if (!selected.isEmpty()) {
            col.addChild(new UiKit.Label(Component.literal(selected.getCount() + "x ").append(selected.getHoverName()), UiKit.TEXT, w));
            LinearLayout row = LinearLayout.horizontal().spacing(4);
            row.addChild(numberField("price", w - 4 - 56, Component.translatable("smartphone.market.price"), 9, this::sell));
            row.addChild(new UiKit.Button(56, 18, Component.translatable("smartphone.market.list"), UiKit.Style.FILLED, UiKit.BLUE, this::sell));
            col.addChild(row);
            double fee = ClientPhoneState.serverInfo.marketFeePercent();
            if (fee > 0) col.addChild(new UiKit.Label(Component.translatable("smartphone.market.fee_hint", (int) fee), UiKit.TEXT_2, w));
        }
        status(col);
    }

    private void sell() {
        long price = number("price");
        if (selectedSlot < 0 || price <= 0) return;
        ClientPlayNetworking.send(new C2S.MarketSell(selectedSlot, price));
        selectedSlot = -1;
        fields.put("price", "");
    }

    private void mineTab(LinearLayout col) {
        status(col);
        UiKit.ScrollList list = list("mine", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        List<S2C.ListingEntry> mine = ClientPhoneState.listings.stream().filter(S2C.ListingEntry::mine).toList();
        if (mine.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.market.none_mine"), UiKit.TEXT_3));
        for (S2C.ListingEntry l : mine) {
            rows.add(new UiKit.ListRow(itemIcon(l.item()), l.item().getHoverName(),
                    Component.translatable("smartphone.market.for_sale"), Component.literal(l.price() + " ◆"), UiKit.TEXT, null)
                    .button(Component.translatable("smartphone.market.take_back"), UiKit.ORANGE,
                            () -> ClientPlayNetworking.send(new C2S.MarketAction(l.id(), false))));
        }
        list.rows(rows);
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.MARKET || update == PhoneScreen.Update.BANK) phone.refresh();
    }

    /** The player's 36 inventory slots; click one to select it for sale. */
    private class InventoryGrid extends UiKit.Widget {
        private final int cell;

        InventoryGrid(int width) {
            super(width, 0);
            this.cell = Math.min(18, width / 9);
            this.height = cell * 4 + 3;
            this.active = true;
        }

        private int slotAt(double mx, double my) {
            int col = (int) ((mx - getX()) / cell);
            int row = (int) ((my - getY()) / cell);
            if (col < 0 || col >= 9 || row < 0 || row >= 4) return -1;
            // Hotbar (slots 0-8) is drawn as the bottom row, like the inventory screen.
            return row == 3 ? col : 9 + row * 9 + col;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            int slot = slotAt(event.x(), event.y());
            if (slot < 0 || mc.player == null) return;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) return;
            if (stack.is(ModItems.SMARTPHONE) && stack == mc.player.getMainHandItem()) return;
            selectedSlot = slot == selectedSlot ? -1 : slot;
            UiKit.click();
            phone.refresh();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            if (mc.player == null) return;
            int hover = isHovered() ? slotAt(mouseX, mouseY) : -1;
            for (int row = 0; row < 4; row++) {
                for (int c = 0; c < 9; c++) {
                    int slot = row == 3 ? c : 9 + row * 9 + c;
                    int x = getX() + c * cell, y = getY() + row * cell + (row == 3 ? 3 : 0);
                    int bg = slot == selectedSlot ? UiKit.BLUE : slot == hover ? UiKit.SURFACE_3 : UiKit.SURFACE_2;
                    UiKit.roundRect(g, x, y, cell - 1, cell - 1, 3, bg);
                    ItemStack stack = mc.player.getInventory().getItem(slot);
                    if (!stack.isEmpty()) {
                        g.pose().pushMatrix();
                        float s = (cell - 2) / 16f;
                        g.pose().translate(x, y);
                        g.pose().scale(s, s);
                        g.item(stack, 0, 0);
                        g.itemDecorations(font, stack, 0, 0);
                        g.pose().popMatrix();
                    }
                }
            }
            if (hover >= 0) {
                ItemStack stack = mc.player.getInventory().getItem(hover);
                if (!stack.isEmpty()) g.setTooltipForNextFrame(font, stack, mouseX, mouseY);
            }
        }
    }
}
