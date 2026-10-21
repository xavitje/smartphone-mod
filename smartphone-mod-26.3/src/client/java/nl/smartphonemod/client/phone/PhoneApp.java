package nl.smartphonemod.client.phone;

import nl.smartphonemod.client.ui.PhoneField;
import nl.smartphonemod.client.ui.UiKit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * One app on the phone. Apps build their widgets into a vertical {@link LinearLayout};
 * the {@link PhoneScreen} positions it. Any state that must survive a rebuild (typed text,
 * scroll position, selected tab) lives in fields of the app, so data updates can simply rebuild.
 */
public abstract class PhoneApp {
    protected PhoneScreen phone;
    protected final Minecraft mc = Minecraft.getInstance();
    protected final Font font = mc.font;

    /** Text typed into fields, by key. */
    protected final Map<String, String> fields = new HashMap<>();
    /** Scroll positions of lists, by key. */
    protected final Map<String, Double> scrolls = new HashMap<>();
    protected String focusKey;

    /** Width available for content. */
    protected int w;
    /** Height available for content. */
    protected int h;

    /** The scroll list that should take up the remaining height (set during build). */
    UiKit.ScrollList filler;

    /** Short id, used to route notifications (e.g. "messages"). */
    public abstract String id();

    /** Builds the app's widgets into {@code col}. */
    protected abstract void build(LinearLayout col);

    /** Optional widgets pinned to the bottom of the screen (dock, chat input). */
    protected void buildBottom(LinearLayout bottom) {}

    /** New data arrived from the server. Default: rebuild. */
    protected void onUpdate(PhoneScreen.Update update) {
        phone.refresh();
    }

    protected void tick() {}

    /** Keyboard input while no text field is focused (return true when handled). */
    protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return false;
    }

    protected boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        return false;
    }

    /** Called when the app is left (back/home/close). */
    protected void close() {}

    /** Whether the home wallpaper shows behind this app (instead of the dark app background). */
    protected boolean wallpaper() {
        return false;
    }

    /** Whether the phone's glass is see-through (the camera viewfinder shows the world). */
    protected boolean transparent() {
        return false;
    }

    /** Extra drawing below the widgets (positions in screen space). */
    protected void underlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {}

    /** Extra drawing on top of the widgets (positions in screen space). */
    protected void overlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {}

    // ------------------------------------------------------------------ helpers for subclasses

    /** Large bold page title with an optional back button. */
    protected void header(LinearLayout col, Component title, UiKit.Button trailing) {
        LinearLayout top = LinearLayout.horizontal().spacing(4);
        if (phone.canGoBack()) {
            top.addChild(new UiKit.Button(34, 14, Component.literal("‹ ").append(Component.translatable("smartphone.nav.back")),
                    UiKit.Style.PLAIN, UiKit.BLUE, phone::back));
        }
        col.addChild(top);
        LinearLayout titleRow = LinearLayout.horizontal().spacing(4);
        int titleWidth = w - (trailing == null ? 0 : trailing.getWidth() + 4);
        Component bold = title.copy().withStyle(ChatFormatting.BOLD);
        float scale = Math.max(1f, Math.min(1.5f, (titleWidth - 2) / (float) Math.max(1, font.width(bold))));
        titleRow.addChild(new UiKit.Label(bold, UiKit.TEXT, titleWidth).scale(scale),
                s -> s.alignVertically(0.5f));
        if (trailing != null) titleRow.addChild(trailing, s -> s.alignVertically(0.5f));
        col.addChild(titleRow, s -> s.paddingBottom(2));
    }

    /** Text field for a player name, with a dropdown of online players and contacts to search in. */
    protected PhoneField playerField(String key, int width, Component hint, Runnable onEnter) {
        return field(key, width, hint, 16, onEnter).suggestPlayers(null);
    }

    protected PhoneField field(String key, int width, Component hint, int maxLength, Runnable onEnter) {
        PhoneField box = new PhoneField(font, key, width, 18, hint);
        box.setMaxLength(maxLength);
        box.setValue(fields.getOrDefault(key, ""));
        box.setResponder(v -> fields.put(key, v));
        box.onEnter(onEnter);
        if (key.equals(focusKey)) phone.focusLater(box);
        return box;
    }

    /** Digits-only field. */
    protected PhoneField numberField(String key, int width, Component hint, int maxDigits, Runnable onEnter) {
        PhoneField box = field(key, width, hint, maxDigits, onEnter);
        box.setResponder(v -> {
            String digits = v.replaceAll("\\D", "");
            if (!digits.equals(v)) box.setValue(digits);
            else fields.put(key, v);
        });
        return box;
    }

    protected String text(String key) {
        return fields.getOrDefault(key, "").strip();
    }

    protected long number(String key) {
        try {
            return Long.parseLong(text(key));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A scroll list that keeps its scroll position across rebuilds; optionally fills the remaining height. */
    protected UiKit.ScrollList list(String key, int gap, boolean fill) {
        UiKit.ScrollList list = new UiKit.ScrollList(w, 10, gap);
        Double saved = scrolls.get(key);
        list.onScroll(() -> scrolls.put(key, list.scroll()));
        if (fill) filler = list;
        if (saved != null) phone.afterLayout(() -> list.setScroll(saved));
        return list;
    }

    protected void status(LinearLayout col, String key, String args, boolean ok, long time) {
        if (key == null || key.isEmpty() || System.currentTimeMillis() - time > 15000) return;
        col.addChild(new UiKit.Label(Component.translatable(key, nl.smartphonemod.client.ClientPhoneState.args(args)),
                ok ? UiKit.GREEN : UiKit.RED, w));
    }
}
