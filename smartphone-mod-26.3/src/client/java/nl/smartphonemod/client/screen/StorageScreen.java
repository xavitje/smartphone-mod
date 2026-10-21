package nl.smartphonemod.client.screen;

import nl.smartphonemod.client.phone.apps.HomeApp;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.storage.StorageMenu;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One view of every linked container. Top: search, sort, "store inventory". Middle: a scrollable grid
 * of item types with their totals. Bottom: the player's inventory.
 *
 * <p>Grid clicks (empty hand): left = one stack onto the cursor, right = half a stack, shift-click =
 * a stack straight into the inventory. Holding something: left = store all, right = store one.
 * Shift-click in the inventory stores that stack.
 */
public class StorageScreen extends AbstractContainerScreen<StorageMenu> {
    private static final int TEXT = 0xFF404040;
    private static final int GX = StorageMenu.GRID_X, GY = StorageMenu.GRID_Y, COLS = StorageMenu.COLS,
            ROWS = StorageMenu.ROWS, CELL = StorageMenu.CELL;
    private static final int BAR_X = GX + COLS * CELL + 4, BAR_W = 8;

    /** 0 = amount, 1 = name, 2 = mod/id. Remembered while the game runs. */
    private static int sortMode;
    private static String lastSearch = "";

    private EditBox search;
    private Button sortButton;
    private List<S2C.StorageEntry> visible = List.of();
    private long builtFrom = -1;
    private String builtSearch = null;
    private int builtSort = -1;
    private int row;
    private boolean draggingBar, swallowRelease;

    public StorageScreen(StorageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, StorageMenu.WIDTH, StorageMenu.HEIGHT);
        this.inventoryLabelY = -1000; // no "Inventory" label: the info line sits there
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        search = new EditBox(font, leftPos + GX + 1, topPos + 17, 104, 11, Component.translatable("smartphone.storage.search"));
        search.setMaxLength(50);
        search.setBordered(true);
        search.setHint(Component.translatable("smartphone.storage.search").withColor(0xFF8A8A8A));
        search.setValue(lastSearch);
        search.setResponder(v -> {
            lastSearch = v;
            row = 0;
        });
        addRenderableWidget(search);

        sortButton = addRenderableWidget(Button.builder(sortLabel(), b -> {
                    sortMode = (sortMode + 1) % 3;
                    b.setMessage(sortLabel());
                })
                .bounds(leftPos + GX + 110, topPos + 16, 44, 13)
                .tooltip(Tooltip.create(Component.translatable("smartphone.storage.sort_tip")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("⇩"),
                        b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, StorageMenu.BUTTON_STORE_INVENTORY))
                .bounds(leftPos + GX + 158, topPos + 16, 18, 13)
                .tooltip(Tooltip.create(Component.translatable("smartphone.storage.store_all_tip")))
                .build());
    }

    private static Component sortLabel() {
        return Component.translatable(switch (sortMode) {
            case 1 -> "smartphone.storage.sort.name";
            case 2 -> "smartphone.storage.sort.mod";
            default -> "smartphone.storage.sort.count";
        });
    }

    // ------------------------------------------------------------------ data

    private void rebuildIfNeeded() {
        String q = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        if (builtFrom == menu.contentsVersion && q.equals(builtSearch) && builtSort == sortMode) return;
        builtFrom = menu.contentsVersion;
        builtSearch = q;
        builtSort = sortMode;
        List<S2C.StorageEntry> out = new ArrayList<>();
        boolean modSearch = q.startsWith("@");
        String needle = modSearch ? q.substring(1) : q;
        for (S2C.StorageEntry e : menu.entries) {
            if (!needle.isEmpty()) {
                if (modSearch) {
                    if (!BuiltInRegistries.ITEM.getKey(e.item().getItem()).getNamespace().contains(needle)) continue;
                } else if (!e.item().getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)
                        && !BuiltInRegistries.ITEM.getKey(e.item().getItem()).getPath().contains(needle)) {
                    continue;
                }
            }
            out.add(e);
        }
        Comparator<S2C.StorageEntry> byName = Comparator.comparing(e -> e.item().getHoverName().getString().toLowerCase(Locale.ROOT));
        Comparator<S2C.StorageEntry> byId = Comparator.comparing(e -> BuiltInRegistries.ITEM.getKey(e.item().getItem()).toString());
        out.sort(switch (sortMode) {
            case 1 -> byName;
            case 2 -> byId.thenComparing(byName);
            default -> Comparator.comparingLong(S2C.StorageEntry::count).reversed().thenComparing(byName);
        });
        visible = out;
        row = Math.max(0, Math.min(row, maxRow()));
    }

    private int maxRow() {
        return Math.max(0, (visible.size() + COLS - 1) / COLS - ROWS);
    }

    /** Index into {@link #visible} under the mouse; -1 = empty cell in the grid; -2 = outside the grid. */
    private int cellAt(double mx, double my) {
        int x = (int) Math.floor(mx) - leftPos - GX, y = (int) Math.floor(my) - topPos - GY;
        if (x < 0 || y < 0 || x >= COLS * CELL || y >= ROWS * CELL) return -2;
        int index = (row + y / CELL) * COLS + x / CELL;
        return index < visible.size() ? index : -1;
    }

    private boolean overBar(double mx, double my) {
        return mx >= leftPos + BAR_X && mx < leftPos + BAR_X + BAR_W && my >= topPos + GY && my < topPos + GY + ROWS * CELL;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        rebuildIfNeeded();
        int x0 = leftPos, y0 = topPos, x1 = leftPos + imageWidth, y1 = topPos + imageHeight;
        g.fill(x0, y0, x1, y1, 0xFF000000);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFFC6C6C6);
        g.fill(x0 + 1, y0 + 1, x1 - 2, y0 + 3, 0xFFFFFFFF);
        g.fill(x0 + 1, y0 + 1, x0 + 3, y1 - 2, 0xFFFFFFFF);
        g.fill(x0 + 3, y1 - 3, x1 - 1, y1 - 1, 0xFF555555);
        g.fill(x1 - 3, y0 + 3, x1 - 1, y1 - 1, 0xFF555555);

        // Grid
        int gx = leftPos + GX, gy = topPos + GY;
        g.fill(gx - 1, gy - 1, gx + COLS * CELL + 1, gy + ROWS * CELL + 1, 0xFF373737);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int sx = gx + c * CELL, sy = gy + r * CELL;
                drawSlot(g, sx, sy);
                int index = (row + r) * COLS + c;
                if (index >= visible.size()) continue;
                S2C.StorageEntry e = visible.get(index);
                g.item(e.item(), sx + 1, sy + 1);
            }
        }
        // Counts on top of the items.
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int index = (row + r) * COLS + c;
                if (index >= visible.size()) break;
                String s = shortCount(visible.get(index).count());
                int sx = gx + c * CELL + 1, sy = gy + r * CELL + 1;
                float scale = font.width(s) > 16 ? 0.66f : 1f;
                g.pose().pushMatrix();
                g.pose().translate(sx + 17, sy + 17);
                g.pose().scale(scale, scale);
                g.text(font, s, -font.width(s), -8, 0xFFFFFFFF, true);
                g.pose().popMatrix();
            }
        }
        if (menu.linked == 0) {
            Component hint = Component.translatable("smartphone.storage.empty_hint");
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(hint, COLS * CELL - 16);
            int y = gy + ROWS * CELL / 2 - lines.size() * 5;
            for (var line : lines) {
                g.text(font, line, gx + (COLS * CELL - font.width(line)) / 2, y, 0xFFFFFFFF, true);
                y += 10;
            }
        }
        // Scrollbar
        int bx = leftPos + BAR_X, by = gy, bh = ROWS * CELL;
        g.fill(bx, by - 1, bx + BAR_W, by + bh + 1, 0xFF373737);
        g.fill(bx + 1, by, bx + BAR_W - 1, by + bh, 0xFF8B8B8B);
        int max = maxRow();
        int knobH = max == 0 ? bh : Math.max(12, bh * ROWS / (ROWS + max));
        int knobY = max == 0 ? by : by + (bh - knobH) * row / max;
        g.fill(bx + 1, knobY, bx + BAR_W - 1, knobY + knobH, max == 0 ? 0xFFA0A0A0 : 0xFFE0E0E0);
        g.fill(bx + 1, knobY + knobH - 1, bx + BAR_W - 1, knobY + knobH, 0xFF707070);

        for (var slot : menu.slots) drawSlot(g, leftPos + slot.x - 1, topPos + slot.y - 1);

        // Capacity bar right under the grid (green → orange → red as the network fills up).
        int fy = topPos + GY + ROWS * CELL + 1;
        int fw = COLS * CELL;
        g.fill(gx, fy, gx + fw, fy + 2, 0xFF8B8B8B);
        if (menu.totalSlots > 0) {
            float frac = Math.min(1f, menu.usedSlots / (float) menu.totalSlots);
            int color = frac > 0.9f ? 0xFFD9443A : frac > 0.7f ? 0xFFE0A030 : 0xFF3FA34D;
            g.fill(gx, fy, gx + Math.round(fw * frac), fy + 2, color);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int gx = leftPos + GX, gy = topPos + GY;
        int hovered = cellAt(mouseX, mouseY);
        if (hovered >= -1) {
            int cx = (int) ((mouseX - gx) / CELL), cy = (int) ((mouseY - gy) / CELL);
            g.fill(gx + cx * CELL + 1, gy + cy * CELL + 1, gx + cx * CELL + 17, gy + cy * CELL + 17, 0x80FFFFFF);
        }
        if (menu.totalSlots > 0 && mouseX >= leftPos + GX && mouseX < leftPos + GX + COLS * CELL
                && mouseY >= topPos + GY + ROWS * CELL && mouseY < topPos + GY + ROWS * CELL + 4 && hovered == -2) {
            g.setTooltipForNextFrame(font, Component.translatable("smartphone.storage.capacity",
                    fmt(menu.usedSlots), fmt(menu.totalSlots), Math.round(100f * menu.usedSlots / menu.totalSlots)), mouseX, mouseY);
        }
        if (hovered >= 0 && menu.getCarried().isEmpty()) {
            S2C.StorageEntry e = visible.get(hovered);
            List<Component> lines = new ArrayList<>(getTooltipFromContainerItem(e.item()));
            lines.add(Component.translatable("smartphone.storage.tip.count", fmt(e.count())).withStyle(net.minecraft.ChatFormatting.GOLD));
            lines.add(Component.translatable("smartphone.storage.tip.click").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            g.setTooltipForNextFrame(font, lines, e.item().getTooltipImage(), mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, title, titleLabelX, titleLabelY, TEXT, false);
        if (menu.remote()) {
            Component r = Component.translatable("smartphone.storage.remote");
            g.text(font, r, imageWidth - 8 - font.width(r), titleLabelY, 0xFF2D6CDF, false);
        }
        long items = 0;
        for (S2C.StorageEntry e : menu.entries) items += e.count();
        int y = GY + ROWS * CELL + 5;
        net.minecraft.network.chat.MutableComponent info = Component.translatable("smartphone.storage.info",
                fmt(items), menu.entries.size(), menu.linked).copy();
        if (menu.unavailable > 0) {
            info.append(" · ").append(Component.translatable("smartphone.storage.unloaded", menu.unavailable).withColor(0xFFB86E00));
        }
        g.text(font, info, GX, y, TEXT, false);
    }

    private static void drawSlot(GuiGraphicsExtractor g, int sx, int sy) {
        g.fill(sx, sy, sx + 18, sy + 18, 0xFF8B8B8B);
        g.fill(sx, sy, sx + 17, sy + 1, 0xFF373737);
        g.fill(sx, sy, sx + 1, sy + 17, 0xFF373737);
        g.fill(sx + 1, sy + 17, sx + 18, sy + 18, 0xFFFFFFFF);
        g.fill(sx + 17, sy + 1, sx + 18, sy + 18, 0xFFFFFFFF);
    }

    private static String fmt(long n) {
        return NumberFormat.getIntegerInstance(HomeApp.locale()).format(n);
    }

    /** 999, 1.2k, 12k, 1.2M … (fits a slot). */
    static String shortCount(long n) {
        if (n < 1000) return Long.toString(n);
        String[] units = {"k", "M", "G"};
        double v = n;
        int u = -1;
        while (v >= 1000 && u < units.length - 1) {
            v /= 1000;
            u++;
        }
        String num = v < 10 ? String.format(Locale.ROOT, "%.1f", Math.floor(v * 10) / 10) : Long.toString((long) v);
        if (num.endsWith(".0")) num = num.substring(0, num.length() - 2);
        return num + units[u];
    }

    // ------------------------------------------------------------------ input

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.25f));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (search.isMouseOver(mx, my) && event.button() == 1) { // right-click clears the search, like creative
            search.setValue("");
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (overBar(mx, my) && event.button() == 0) {
            draggingBar = true;
            scrollToMouse(my);
            return true;
        }
        int cell = cellAt(mx, my);
        if (cell >= -1 && (event.button() == 0 || event.button() == 1)) {
            if (search.isFocused()) {
                search.setFocused(false);
                setFocused(null);
            }
            swallowRelease = true;
            ItemStack carried = menu.getCarried();
            if (!carried.isEmpty()) {
                ClientPlayNetworking.send(new C2S.StorageInsert(menu.containerId, event.button() == 1));
                click();
                return true;
            }
            if (cell < 0) return true;
            S2C.StorageEntry e = visible.get(cell);
            int max = e.item().getMaxStackSize();
            boolean shift = event.hasShiftDown();
            int amount = shift || event.button() == 0 ? max : (int) Math.max(1, (Math.min(e.count(), max) + 1) / 2);
            ClientPlayNetworking.send(new C2S.StorageExtract(menu.containerId, e.item().copyWithCount(1), amount, shift));
            click();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingBar = false;
        if (swallowRelease) {
            swallowRelease = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingBar) {
            scrollToMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    private void scrollToMouse(double my) {
        int max = maxRow();
        if (max == 0) return;
        double t = (my - topPos - GY - 6) / (ROWS * CELL - 12.0);
        row = (int) Math.round(Math.max(0, Math.min(1, t)) * max);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (cellAt(x, y) >= -1 || overBar(x, y)) {
            row = Math.max(0, Math.min(maxRow(), row - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (search.isFocused()) {
            if (event.isEscape()) {
                search.setFocused(false);
                setFocused(null);
                return true;
            }
            if (search.keyPressed(event)) return true;
            return true; // typing must not close the screen (E) or drop items (Q)
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (search.isFocused() && search.charTyped(event)) return true;
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        super.onClose();
        // Opened from the phone: go back to the phone (Storage app) instead of the game.
        if (menu.remote()) ClientPlayNetworking.send(C2S.OpenRequest.INSTANCE);
    }
}
