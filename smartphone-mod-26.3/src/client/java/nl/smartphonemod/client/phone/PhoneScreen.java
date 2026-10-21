package nl.smartphonemod.client.phone;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.client.ClientConfig;
import nl.smartphonemod.client.hud.Notifications;
import nl.smartphonemod.client.phone.apps.ChatApp;
import nl.smartphonemod.client.phone.apps.HomeApp;
import nl.smartphonemod.client.phone.apps.PhotosApp;
import nl.smartphonemod.client.ui.PhoneField;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.Conv;
import nl.smartphonemod.registry.ModItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The phone itself: frame, status bar, wallpaper, home indicator, animations and app navigation.
 * Apps ({@link PhoneApp}) provide the content. Pure presentation: every action is a request to the
 * server, and every number shown came from it.
 */
public class PhoneScreen extends Screen {
    public enum Update { BANK, CONTACTS, THREAD, NEW_MESSAGE, UNREAD, MAP, MARKET, GALLERY, PHOTO, POSITIONS, ALERT, STORAGE, PARKOUR, CCTV }

    public static final int DEFAULT_CASE = 0xFF333337;
    private static final int PAD = 5;
    private static final long OPEN_MS = 260, NAV_MS = 200, SHAKE_MS = 380, BANNER_MS = 3500;

    /** Set by the camera: reopen into the Photos app after a photo was taken. */
    public static boolean pendingOpenPhotos;

    private final Deque<PhoneApp> stack = new ArrayDeque<>();

    // geometry
    private int phoneX, phoneY, phoneW, phoneH;
    private int screenX, screenY, screenW, screenH, radius;
    private int appX, appY, appW, appH;

    // animation
    private long openedAt = System.currentTimeMillis();
    private long navAt;
    private int navDir;
    private long shakeAt;
    private Notifications.Note banner;
    private long bannerAt;

    private final List<Runnable> afterLayout = new ArrayList<>();
    private PhoneField focusLater;

    public PhoneScreen() {
        this(new HomeApp());
    }

    public PhoneScreen(PhoneApp first) {
        super(Component.translatable("smartphone.title"));
        first.phone = this;
        stack.push(first);
    }

    /** Reopens the phone after the camera (into Photos when a picture was taken). */
    public static PhoneScreen reopen() {
        PhoneScreen screen = new PhoneScreen();
        screen.openedAt = 0; // no slide-in
        if (pendingOpenPhotos) screen.open(new PhotosApp(true));
        pendingOpenPhotos = false;
        return screen;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // a phone should not freeze the world in singleplayer
    }

    public int appX() {
        return appX;
    }

    public int appY() {
        return appY;
    }

    public PhoneApp current() {
        return stack.peek();
    }

    // ------------------------------------------------------------------ navigation

    public boolean canGoBack() {
        return stack.size() > 1;
    }

    public void open(PhoneApp app) {
        app.phone = this;
        PhoneApp leaving = current();
        stack.push(app);
        if (leaving != null && !(leaving instanceof HomeApp)) leaving.close();
        startNav(1);
    }

    public void back() {
        if (stack.size() <= 1) return;
        stack.pop().close();
        startNav(-1);
    }

    public void home() {
        while (stack.size() > 1) stack.pop().close();
        startNav(-1);
    }

    /** Replaces the current app (no back entry), e.g. after creating a group. */
    public void replace(PhoneApp app) {
        app.phone = this;
        if (stack.size() > 1) stack.pop().close();
        stack.push(app);
        startNav(1);
    }

    private void startNav(int dir) {
        navAt = System.currentTimeMillis();
        navDir = dir;
        rebuildWidgets();
    }

    /** Rebuilds the current app's widgets, keeping typed text, focus and scroll positions. */
    public void refresh() {
        PhoneApp app = current();
        if (app != null) app.focusKey = getFocused() instanceof PhoneField f ? f.key() : null;
        rebuildWidgets();
    }

    public void focusLater(PhoneField box) {
        focusLater = box;
    }

    public void afterLayout(Runnable r) {
        afterLayout.add(r);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        ClientConfig.updateTheme();
        phoneH = Math.max(210, Math.min(310, height - 14));
        phoneW = Math.min(210, Math.max(160, (int) (phoneH * 0.55)));
        phoneX = (width - phoneW) / 2;
        phoneY = (height - phoneH) / 2;
        int bezel = 4;
        screenX = phoneX + bezel;
        screenY = phoneY + bezel;
        screenW = phoneW - 2 * bezel;
        screenH = phoneH - 2 * bezel;
        radius = 14;
        appX = screenX + PAD;
        appY = screenY + 16;
        appW = screenW - 2 * PAD;
        appH = screenH - 16 - 10;

        PhoneApp app = current();
        if (app == null) return;
        app.phone = this;
        app.w = appW;
        app.h = appH;
        app.filler = null;
        afterLayout.clear();
        focusLater = null;

        LinearLayout col = LinearLayout.vertical().spacing(4);
        LinearLayout bottom = LinearLayout.vertical().spacing(4);
        app.build(col);
        app.buildBottom(bottom);
        bottom.arrangeElements();
        int bottomH = bottom.getHeight() > 0 ? bottom.getHeight() + 4 : 0;
        col.arrangeElements();
        if (app.filler != null) {
            app.filler.setHeightTo(app.filler.getHeight() + appH - bottomH - col.getHeight());
            col.arrangeElements();
        }
        col.setPosition(appX, appY);
        col.visitWidgets(this::addRenderableWidget);
        if (bottomH > 0) {
            bottom.setPosition(appX, appY + appH - bottom.getHeight());
            bottom.visitWidgets(this::addRenderableWidget);
        }
        for (Runnable r : afterLayout) r.run();
        afterLayout.clear();
        if (focusLater != null) setInitialFocus(focusLater);
    }

    // ------------------------------------------------------------------ rendering

    private static float easeOut(float t) {
        t = Mth.clamp(t, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private float openOffsetY() {
        float t = (System.currentTimeMillis() - openedAt) / (float) OPEN_MS;
        return (1 - easeOut(t)) * (height - phoneY + 10);
    }

    private float shakeX() {
        long age = System.currentTimeMillis() - shakeAt;
        if (age > SHAKE_MS) return 0;
        return (float) Math.sin(age / 18.0) * 2.5f * (1 - age / (float) SHAKE_MS);
    }

    private float navOffsetX() {
        float t = (System.currentTimeMillis() - navAt) / (float) NAV_MS;
        return t >= 1 ? 0 : (1 - easeOut(t)) * screenW * 0.35f * navDir;
    }

    private boolean animating() {
        long now = System.currentTimeMillis();
        return now - openedAt < OPEN_MS || now - navAt < NAV_MS;
    }

    /** Colour of the phone's case: the dye on the phone in hand (or the first phone in the inventory). */
    private int caseColor() {
        if (minecraft.player == null) return DEFAULT_CASE;
        ItemStack stack = minecraft.player.getMainHandItem();
        if (!stack.is(ModItems.SMARTPHONE)) stack = minecraft.player.getOffhandItem();
        if (!stack.is(ModItems.SMARTPHONE)) {
            var inv = minecraft.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(ModItems.SMARTPHONE)) {
                    stack = inv.getItem(i);
                    break;
                }
            }
        }
        DyedItemColor dye = stack.get(DataComponents.DYED_COLOR);
        return dye == null ? DEFAULT_CASE : 0xFF000000 | dye.rgb();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        PhoneApp app = current();
        boolean glass = app != null && app.transparent();
        if (!glass) extractTransparentBackground(g);
        g.pose().pushMatrix();
        g.pose().translate(shakeX(), openOffsetY());

        int caseColor = caseColor();
        // Side buttons
        g.fill(phoneX - 1, phoneY + 40, phoneX, phoneY + 56, UiKit.shade(caseColor, 0.7f));
        g.fill(phoneX + phoneW, phoneY + 46, phoneX + phoneW + 1, phoneY + 70, UiKit.shade(caseColor, 0.7f));
        if (glass) {
            // Viewfinder: leave the glass see-through, only draw the bezel ring around it.
            g.pose().popMatrix();
            drawBezelRing(g, caseColor);
            return;
        }
        // Frame and glass
        UiKit.roundRect(g, phoneX, phoneY, phoneW, phoneH, radius + 4, UiKit.shade(caseColor, 1.25f));
        UiKit.roundRect(g, phoneX + 1, phoneY + 1, phoneW - 2, phoneH - 2, radius + 3, caseColor);
        UiKit.roundRect(g, screenX - 1, screenY - 1, screenW + 2, screenH + 2, radius + 1, 0xFF050505);

        if (app != null && app.wallpaper()) {
            Identifier wp = SmartphoneMod.id("textures/gui/wallpaper/" + ClientConfig.get().wallpaper + ".png");
            g.blit(RenderPipelines.GUI_TEXTURED, wp, screenX, screenY, 0, 0, screenW, screenH, 180, 380, 180, 380);
        } else {
            g.fill(screenX, screenY, screenX + screenW, screenY + screenH, UiKit.BG);
        }
        UiKit.cornerMask(g, screenX, screenY, screenW, screenH, radius, 0xFF050505);
        g.pose().popMatrix();
    }

    /** For see-through apps: the case is drawn as a ring (four bands) so the middle stays clear. */
    private void drawBezelRing(GuiGraphicsExtractor g, int caseColor) {
        g.pose().pushMatrix();
        g.pose().translate(shakeX(), openOffsetY());
        g.fill(phoneX, phoneY, phoneX + phoneW, screenY, caseColor);
        g.fill(phoneX, screenY + screenH, phoneX + phoneW, phoneY + phoneH, caseColor);
        g.fill(phoneX, screenY, screenX, screenY + screenH, caseColor);
        g.fill(screenX + screenW, screenY, phoneX + phoneW, screenY + screenH, caseColor);
        UiKit.cornerMask(g, screenX, screenY, screenW, screenH, radius, caseColor);
        g.pose().popMatrix();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        float ox = shakeX(), oy = openOffsetY();
        boolean anim = animating();
        int mx = anim ? -1000 : mouseX, my = anim ? -1000 : mouseY;

        // App content, clipped to the glass and slid in during navigation.
        g.pose().pushMatrix();
        g.pose().translate(ox + navOffsetX(), oy);
        g.enableScissor(screenX, screenY + 14, screenX + screenW, screenY + screenH - 8);
        if (current() != null) current().underlay(g, mx, my);
        super.extractRenderState(g, mx, my, a);
        if (current() != null) current().overlay(g, mx, my);
        g.disableScissor();
        g.pose().popMatrix();

        // Chrome on top: status bar, dynamic island, home indicator, banner.
        g.pose().pushMatrix();
        g.pose().translate(ox, oy);
        drawStatusBar(g);
        drawHomeIndicator(g, mouseX, mouseY);
        drawBanner(g);
        drawDropdown(g, mouseX, mouseY);
        g.pose().popMatrix();
    }

    private void drawStatusBar(GuiGraphicsExtractor g) {
        int y = screenY + 4;
        LocalTime now = LocalTime.now();
        String time = ClientConfig.get().use24h ? String.format("%d:%02d", now.getHour(), now.getMinute())
                : String.format("%d:%02d", (now.getHour() + 11) % 12 + 1, now.getMinute());
        PhoneApp app = current();
        int fg = app == null || app.wallpaper() || app.transparent() || !UiKit.isLight() ? UiKit.WHITE : UiKit.BLACK;
        g.text(font, time, screenX + 12, y + 1, fg, false);
        // Dynamic island
        UiKit.roundRect(g, screenX + screenW / 2 - 18, screenY + 3, 36, 10, 5, 0xFF000000);
        // Signal bars, wifi and battery glyphs
        int rx = screenX + screenW - 12;
        UiKit.roundRect(g, rx - 14, y + 1, 14, 7, 2, 0xFF8E8E93);
        UiKit.roundRect(g, rx - 13, y + 2, 10, 5, 1, fg);
        g.fill(rx, y + 3, rx + 1, y + 6, 0xFF8E8E93);
        int sx = rx - 32;
        for (int i = 0; i < 4; i++) g.fill(sx + i * 3, y + 7 - (i + 1) * 2 + 1, sx + i * 3 + 2, y + 8, fg);
    }

    private boolean overHomeIndicator(int mouseX, int mouseY) {
        int cx = screenX + screenW / 2;
        return mouseX >= cx - 30 && mouseX <= cx + 30 && mouseY >= screenY + screenH - 9 && mouseY <= screenY + screenH;
    }

    private void drawHomeIndicator(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int cx = screenX + screenW / 2;
        boolean hover = overHomeIndicator(mouseX, mouseY);
        PhoneApp app = current();
        boolean dark = app != null && !app.wallpaper() && !app.transparent() && UiKit.isLight();
        UiKit.roundRect(g, cx - 22, screenY + screenH - 6, 44, 3, 1, dark ? (hover ? 0xFF000000 : 0xCC000000) : (hover ? 0xFFFFFFFF : 0xCCFFFFFF));
    }

    /** Shows a notification banner at the top of the phone (and shakes if vibration is on). */
    public void banner(Notifications.Note note, boolean vibrate) {
        banner = note;
        bannerAt = System.currentTimeMillis();
        if (vibrate) shakeAt = bannerAt;
    }

    private int bannerHeight() {
        return 30;
    }

    private void drawBanner(GuiGraphicsExtractor g) {
        if (banner == null) return;
        long age = System.currentTimeMillis() - bannerAt;
        if (age > BANNER_MS) {
            banner = null;
            return;
        }
        float t = Math.min(easeOut(age / 200f), easeOut((BANNER_MS - age) / 200f));
        int bw = screenW - 8, bh = bannerHeight();
        int bx = screenX + 4, by = screenY + 3 + (int) ((t - 1) * (bh + 6));
        UiKit.roundRect(g, bx, by, bw, bh, 9, UiKit.withAlpha(UiKit.SURFACE_2, 0xF2));
        g.blit(RenderPipelines.GUI_TEXTURED, Notifications.icon(banner.app()), bx + 5, by + 6, 0, 0, 18, 18, 64, 64, 64, 64);
        g.text(font, UiKit.ellipsize(banner.title().getString(), bw - 34), bx + 28, by + 5, UiKit.TEXT, false);
        g.text(font, UiKit.ellipsize(banner.body().getString(), bw - 34), bx + 28, by + 16, UiKit.TEXT_2, false);
    }

    private boolean overBanner(double mouseX, double mouseY) {
        return banner != null && mouseX >= screenX + 4 && mouseX <= screenX + screenW - 4
                && mouseY >= screenY + 3 && mouseY <= screenY + 3 + bannerHeight();
    }

    // ------------------------------------------------------------------ input

    private boolean insidePhone(double x, double y) {
        return x >= phoneX && x <= phoneX + phoneW && y >= phoneY && y <= phoneY + phoneH;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (animating()) return true;
        if (dropdownClick(event.x(), event.y())) return true;
        if (!insidePhone(event.x(), event.y())) {
            onClose(); // tapping next to the phone puts it away
            return true;
        }
        if (overBanner(event.x(), event.y())) {
            String app = banner.app();
            banner = null;
            if ("messages".equals(app) && !(current() instanceof ChatApp)) open(new nl.smartphonemod.client.phone.apps.MessagesApp());
            else if (!(current() != null && app.equals(current().id()))) {
                PhoneApp target = HomeApp.create(app);
                if (target != null) open(target);
            }
            UiKit.click();
            return true;
        }
        if (overHomeIndicator((int) event.x(), (int) event.y())) {
            UiKit.click();
            home();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (getFocused() instanceof PhoneField field) {
            if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB && field.isFocused()) {
                List<nl.smartphonemod.client.PlayerNames.Entry> m = dropdownEntries(field);
                if (!m.isEmpty()) {
                    pickSuggestion(field, m.get(0).name());
                    return true;
                }
            }
            if (event.isConfirmation() && field.enter()) return true;
            return super.keyPressed(event);
        }
        // Not typing: the phone hotkey or the inventory key closes the phone, like Escape.
        if (nl.smartphonemod.client.PhoneKeys.OPEN.matches(event) || minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        if (current() != null && !animating() && current().keyPressed(event)) return true;
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (!(getFocused() instanceof PhoneField) && current() != null && !animating() && current().charTyped(event)) return true;
        return super.charTyped(event);
    }

    // ------------------------------------------------------------------ player dropdown

    private static final int DROP_ROW = 13, DROP_MAX = 5;

    private List<nl.smartphonemod.client.PlayerNames.Entry> dropdownEntries(PhoneField field) {
        if (!field.hasSuggestions() || !field.isFocused()) return List.of();
        List<nl.smartphonemod.client.PlayerNames.Entry> list = nl.smartphonemod.client.PlayerNames.search(field.getValue(), DROP_MAX);
        if (list.size() == 1 && list.get(0).name().equalsIgnoreCase(field.getValue().trim())) return List.of();
        return list;
    }

    /** Dropdown position: below the field, or above it when there is no room. */
    private int[] dropdownBox(PhoneField field, int rows) {
        int h = rows * DROP_ROW + 4;
        int y = field.getY() + field.getHeight() + 1;
        if (y + h > screenY + screenH - 10) y = field.getY() - h - 1;
        int w = Math.max(field.getWidth(), 100);
        int x = Math.min(field.getX(), screenX + screenW - 4 - w);
        return new int[]{x, y, w, h};
    }

    private void drawDropdown(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!(getFocused() instanceof PhoneField field)) return;
        List<nl.smartphonemod.client.PlayerNames.Entry> list = dropdownEntries(field);
        if (list.isEmpty()) return;
        int[] b = dropdownBox(field, list.size());
        UiKit.roundRect(g, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, 7, UiKit.SURFACE_3);
        UiKit.roundRect(g, b[0], b[1], b[2], b[3], 6, UiKit.SURFACE);
        for (int i = 0; i < list.size(); i++) {
            var e = list.get(i);
            int ry = b[1] + 2 + i * DROP_ROW;
            boolean hover = mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= ry && mouseY < ry + DROP_ROW;
            if (hover) g.fill(b[0] + 2, ry, b[0] + b[2] - 2, ry + DROP_ROW, UiKit.SURFACE_2);
            UiKit.circle(g, b[0] + 7, ry + 6, 2, e.online() ? UiKit.GREEN : UiKit.TEXT_3);
            g.text(font, UiKit.ellipsize(e.name(), b[2] - 18), b[0] + 13, ry + 3, UiKit.TEXT, false);
        }
    }

    private boolean dropdownClick(double mx, double my) {
        if (!(getFocused() instanceof PhoneField field)) return false;
        List<nl.smartphonemod.client.PlayerNames.Entry> list = dropdownEntries(field);
        if (list.isEmpty()) return false;
        int[] b = dropdownBox(field, list.size());
        if (mx < b[0] || mx >= b[0] + b[2] || my < b[1] || my >= b[1] + b[3]) return false;
        int i = (int) ((my - b[1] - 2) / DROP_ROW);
        if (i >= 0 && i < list.size()) pickSuggestion(field, list.get(i).name());
        return true;
    }

    private void pickSuggestion(PhoneField field, String name) {
        UiKit.click();
        field.setValue(name);
        field.moveCursorToEnd(false);
        field.pickedSuggestion();
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft.player != null && minecraft.player.tickCount % 20 == 0 && ClientConfig.updateTheme()) refresh();
        if (!pendingUpdates.isEmpty() && !animating() && current() != null) {
            List<Update> todo = new ArrayList<>(pendingUpdates);
            pendingUpdates.clear();
            for (Update u : todo) current().onUpdate(u);
        }
        if (current() != null) current().tick();
    }

    @Override
    public void removed() {
        for (PhoneApp app : stack) app.close();
        super.removed();
    }

    // ------------------------------------------------------------------ data updates

    /** Called by the network layer when new server data arrived. */
    public void onUpdate(Update update) {
        if (current() == null) return;
        if (animating()) pendingUpdates.add(update); // applied once the slide is over (see tick)
        else current().onUpdate(update);
    }

    private final java.util.Set<Update> pendingUpdates = java.util.EnumSet.noneOf(Update.class);

    public boolean isViewingThread(Conv conv) {
        return current() instanceof ChatApp chat && chat.conv().equals(conv);
    }

    @Override
    public GuiEventListener getFocused() {
        return super.getFocused();
    }
}
