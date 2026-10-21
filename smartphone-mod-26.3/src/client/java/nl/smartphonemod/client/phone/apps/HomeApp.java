package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.client.ClientConfig;
import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.ui.UiKit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Home screen: date & time widget, app grid and a dock, on the chosen wallpaper. */
public class HomeApp extends PhoneApp {
    private record AppDef(String id, String nameKey, Supplier<PhoneApp> factory) {}

    private static final List<AppDef> GRID = List.of(
            new AppDef("market", "smartphone.app.market", MarketApp::new),
            new AppDef("photos", "smartphone.app.photos", () -> new PhotosApp(false)),
            new AppDef("clock", "smartphone.app.clock", ClockApp::new),
            new AppDef("calculator", "smartphone.app.calculator", CalculatorApp::new),
            new AppDef("storage", "smartphone.app.storage", StorageApp::new),
            new AppDef("parkour", "smartphone.app.parkour", ParkourApp::new),
            new AppDef("cctv", "smartphone.app.cctv", CctvApp::new),
            new AppDef("settings", "smartphone.app.settings", SettingsApp::new),
            new AppDef("emergency", "smartphone.app.emergency", EmergencyApp::new));
    private static final List<AppDef> DOCK = List.of(
            new AppDef("messages", "smartphone.app.messages", MessagesApp::new),
            new AppDef("bank", "smartphone.app.bank", BankApp::new),
            new AppDef("map", "smartphone.app.map", MapApp::new),
            new AppDef("camera", "smartphone.app.camera", CameraApp::new));

    private static final int ICON = 30;

    /** Creates an app by id (used when a notification banner is tapped). */
    public static PhoneApp create(String id) {
        for (AppDef d : GRID) if (d.id().equals(id)) return d.factory().get();
        for (AppDef d : DOCK) if (d.id().equals(id)) return d.factory().get();
        return null;
    }

    @Override
    public String id() {
        return "home";
    }

    @Override
    protected boolean wallpaper() {
        return true;
    }

    private static Identifier icon(String id) {
        return SmartphoneMod.id("textures/gui/app/" + id + ".png");
    }

    @Override
    protected void build(LinearLayout col) {
        col.addChild(SpacerElement.width(w));
        // Lock-screen style clock (real time) and date.
        col.addChild(new UiKit.LiveLabel(() -> Component.literal(LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern(ClientConfig.get().use24h ? "H:mm" : "h:mm"))), UiKit.WHITE, w)
                .centered().scale(3.2f), s -> s.paddingTop(6));
        col.addChild(new UiKit.LiveLabel(() -> Component.literal(LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("EEEE d MMMM", locale()))), UiKit.WHITE, w).centered());
        long day = mc.level == null ? 0 : mc.level.getOverworldClockTime() / 24000L + 1;
        col.addChild(new UiKit.Label(Component.translatable("smartphone.home.day", day), 0xCCFFFFFF, w).centered()
                .scale(0.85f), s -> s.paddingBottom(10));

        int columns = 4;
        int gap = 2;
        tileW = (w - (columns - 1) * gap) / columns;
        icon = Math.min(ICON, tileW - 6);
        List<AppDef> grid = GRID.stream().filter(d -> !d.id().equals("cctv") || ClientPhoneState.cctvView).toList();
        for (int i = 0; i < grid.size(); i += columns) {
            LinearLayout row = LinearLayout.horizontal().spacing(gap);
            for (int j = i; j < Math.min(grid.size(), i + columns); j++) row.addChild(tile(grid.get(j), true));
            col.addChild(row, s -> s.paddingBottom(4));
        }

        this.gap = gap;
    }

    private int gap = 2, tileW = 36, icon = ICON;

    /** The dock sits at the bottom of the screen. */
    @Override
    protected void buildBottom(LinearLayout bottom) {
        LinearLayout dock = LinearLayout.horizontal().spacing(gap);
        for (AppDef d : DOCK) dock.addChild(tile(d, false));
        bottom.addChild(dock, s -> s.paddingBottom(6));
        lastDock = dock;
    }

    private LinearLayout lastDock;

    private UiKit.AppTile tile(AppDef def, boolean label) {
        int badge = "messages".equals(def.id()) ? ClientPhoneState.unread
                : "bank".equals(def.id()) ? (int) ClientPhoneState.requests.stream().filter(r -> r.incoming()).count() : 0;
        return new UiKit.AppTile(tileW, icon(def.id()), Component.translatable(def.nameKey()), badge, icon, label,
                () -> phone.open(def.factory().get()));
    }

    @Override
    protected void underlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (lastDock == null) return;
        // Frosted dock panel behind the four dock icons.
        int x = lastDock.getX() - 5, y = lastDock.getY() - 4;
        UiKit.roundRect(g, x, y, lastDock.getWidth() + 10, lastDock.getHeight() + 8, 12, 0x55FFFFFF);
    }

    public static Locale locale() {
        String code = net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected();
        return code.startsWith("nl") ? Locale.forLanguageTag("nl-NL") : Locale.ENGLISH;
    }

    @Override
    protected void onUpdate(nl.smartphonemod.client.phone.PhoneScreen.Update update) {
        if (update == nl.smartphonemod.client.phone.PhoneScreen.Update.POSITIONS) return;
        phone.refresh();
    }
}
