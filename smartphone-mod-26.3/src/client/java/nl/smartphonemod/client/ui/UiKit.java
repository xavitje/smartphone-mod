package nl.smartphonemod.client.ui;

import nl.smartphonemod.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The phone's widget kit, styled after a modern dark-mode phone OS. Widgets only know their own size;
 * their positions come from vanilla {@link net.minecraft.client.gui.layouts.LinearLayout}s.
 */
public final class UiKit {
    // Palette: dark mode by default, switched by applyTheme(). Text on coloured backgrounds uses WHITE.
    public static int BG = 0xFF000000, SURFACE = 0xFF1C1C1E, SURFACE_2 = 0xFF2C2C2E, SURFACE_3 = 0xFF3A3A3C;
    public static int SEPARATOR = 0xFF38383A;
    public static int TEXT = 0xFFFFFFFF, TEXT_2 = 0xFFAEAEB2, TEXT_3 = 0xFF6E6E73;
    public static int BLUE = 0xFF0A84FF, GREEN = 0xFF30D158, RED = 0xFFFF453A, ORANGE = 0xFFFF9F0A,
            YELLOW = 0xFFFFD60A, PURPLE = 0xFFBF5AF2, TEAL = 0xFF40C8E0, PINK = 0xFFFF375F;
    public static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;
    private static boolean light;

    /** Switches between the dark and the light palette. Returns true when it changed. */
    public static boolean applyTheme(boolean lightMode) {
        if (lightMode == light) return false;
        light = lightMode;
        if (lightMode) {
            BG = 0xFFF2F2F7; SURFACE = 0xFFFFFFFF; SURFACE_2 = 0xFFE5E5EA; SURFACE_3 = 0xFFD1D1D6; SEPARATOR = 0xFFC6C6C8;
            TEXT = 0xFF000000; TEXT_2 = 0xFF6C6C70; TEXT_3 = 0xFF9C9CA1;
            BLUE = 0xFF007AFF; GREEN = 0xFF28A745; RED = 0xFFFF3B30; ORANGE = 0xFFF08A00;
            YELLOW = 0xFFD9A400; PURPLE = 0xFFAF52DE; TEAL = 0xFF20A4C0; PINK = 0xFFFF2D55;
        } else {
            BG = 0xFF000000; SURFACE = 0xFF1C1C1E; SURFACE_2 = 0xFF2C2C2E; SURFACE_3 = 0xFF3A3A3C; SEPARATOR = 0xFF38383A;
            TEXT = 0xFFFFFFFF; TEXT_2 = 0xFFAEAEB2; TEXT_3 = 0xFF6E6E73;
            BLUE = 0xFF0A84FF; GREEN = 0xFF30D158; RED = 0xFFFF453A; ORANGE = 0xFFFF9F0A;
            YELLOW = 0xFFFFD60A; PURPLE = 0xFFBF5AF2; TEAL = 0xFF40C8E0; PINK = 0xFFFF375F;
        }
        return true;
    }

    public static boolean isLight() {
        return light;
    }

    /** Readable text colour (black or white) on top of the given background. */
    public static int onColor(int bg) {
        int r = (bg >> 16) & 255, g = (bg >> 8) & 255, b = bg & 255;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 170 ? BLACK : WHITE;
    }

    private UiKit() {}

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    /** Lighter/darker variant of a colour (factor &gt; 1 lightens). */
    public static int shade(int color, float factor) {
        int a = color >>> 24, r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        if (factor >= 1) {
            r = (int) (r + (255 - r) * (factor - 1));
            g = (int) (g + (255 - g) * (factor - 1));
            b = (int) (b + (255 - b) * (factor - 1));
        } else {
            r = (int) (r * factor);
            g = (int) (g * factor);
            b = (int) (b * factor);
        }
        return (a << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    public static void click() {
        SoundManager sm = Minecraft.getInstance().getSoundManager();
        sm.play(SimpleSoundInstance.forUI(ModSounds.TAP, 1.0f, 0.5f));
    }

    /** Stable colour for an avatar, derived from a name. */
    public static int avatarColor(String name) {
        int[] palette = {BLUE, GREEN, ORANGE, PURPLE, TEAL, PINK, RED, 0xFF5E5CE6, 0xFFAC8E68};
        return palette[Math.floorMod(name.hashCode(), palette.length)];
    }

    // ------------------------------------------------------------------ drawing helpers

    /** Filled rectangle with rounded corners (scanline arcs, crisp at GUI scale). */
    public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    /** Paints the area outside a rounded rectangle's corners (to "clip" something drawn as a square). */
    public static void cornerMask(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            if (inset <= 0) continue;
            g.fill(x, y + i, x + inset, y + i + 1, color);
            g.fill(x + w - inset, y + i, x + w, y + i + 1, color);
            g.fill(x, y + h - 1 - i, x + inset, y + h - i, color);
            g.fill(x + w - inset, y + h - 1 - i, x + w, y + h - i, color);
        }
    }

    public static void circle(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        roundRect(g, cx - r, cy - r, 2 * r, 2 * r, r, color);
    }

    /** Round avatar with the first letter of a name. */
    public static void avatar(GuiGraphicsExtractor g, int x, int y, int size, String name, boolean group) {
        circle(g, x + size / 2, y + size / 2, size / 2, group ? SURFACE_3 : avatarColor(name));
        String letter = group ? "●●" : name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        int tw = font().width(letter);
        g.text(font(), letter, x + (size - tw) / 2, y + (size - 8) / 2, group ? TEXT_2 : WHITE, false);
    }

    public static void text(GuiGraphicsExtractor g, Component text, int x, int y, int color) {
        g.text(font(), text, x, y, color, false);
    }

    public static void textRight(GuiGraphicsExtractor g, Component text, int right, int y, int color) {
        g.text(font(), text, right - font().width(text), y, color, false);
    }

    public static void textCentered(GuiGraphicsExtractor g, Component text, int cx, int y, int color) {
        g.text(font(), text, cx - font().width(text) / 2, y, color, false);
    }

    /** Draws text scaled up (e.g. big clock digits). */
    public static void bigText(GuiGraphicsExtractor g, Component text, int x, int y, float scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font(), text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    public static String ellipsize(String s, int maxWidth) {
        if (font().width(s) <= maxWidth) return s;
        String dots = "…";
        while (!s.isEmpty() && font().width(s + dots) > maxWidth) s = s.substring(0, s.length() - 1);
        return s + dots;
    }

    // ------------------------------------------------------------------ base widget

    /** Widget with no narration and optional click action. */
    public abstract static class Widget extends AbstractWidget {
        protected Runnable onClick;

        protected Widget(int width, int height) {
            super(0, 0, width, height, Component.empty());
        }

        public Widget onClick(Runnable action) {
            this.onClick = action;
            this.active = action != null;
            return this;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (onClick != null) onClick.run();
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            if (onClick != null) click();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }

    /** Free-form widget drawn by a lambda. */
    public static class Canvas extends Widget {
        public interface Painter {
            void paint(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hovered, int mouseX, int mouseY);
        }

        private final Painter painter;

        public Canvas(int width, int height, Painter painter) {
            super(width, height);
            this.painter = painter;
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            painter.paint(g, getX(), getY(), width, height, isHovered(), mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------ text

    /** Text that wraps to a maximum width, optionally centred or scaled. Not clickable unless given an action. */
    public static class Label extends Widget {
        private Component text;
        private int color;
        private final int maxWidth;
        private boolean centered;
        private float scale = 1;
        private List<FormattedCharSequence> lines = List.of();

        public Label(Component text, int color, int maxWidth) {
            super(0, 0);
            this.color = color;
            this.maxWidth = maxWidth;
            this.active = false;
            setText(text);
        }

        public Label centered() {
            this.centered = true;
            setText(text);
            return this;
        }

        public Label scale(float scale) {
            this.scale = scale;
            setText(text);
            return this;
        }

        public void setText(Component text) {
            this.text = text;
            this.lines = font().split(text, (int) (maxWidth / scale));
            int w = 0;
            for (FormattedCharSequence line : lines) w = Math.max(w, font().width(line));
            this.width = centered ? maxWidth : Math.max(1, (int) Math.ceil(w * scale));
            this.height = Math.max((int) (9 * scale), (int) Math.ceil((lines.size() * 10 - 1) * scale));
        }

        public void setColor(int color) {
            this.color = color;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            g.pose().pushMatrix();
            g.pose().translate(getX(), getY());
            g.pose().scale(scale, scale);
            int y = 0;
            int innerW = (int) (width / scale);
            for (FormattedCharSequence line : lines) {
                int x = centered ? (innerW - font().width(line)) / 2 : 0;
                g.text(font(), line, x, y, color, false);
                y += 10;
            }
            g.pose().popMatrix();
        }
    }

    /** Label whose text is re-evaluated every frame (clock, stopwatch). */
    public static class LiveLabel extends Label {
        private final Supplier<Component> supplier;

        public LiveLabel(Supplier<Component> supplier, int color, int maxWidth) {
            super(supplier.get(), color, maxWidth);
            this.supplier = supplier;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            setText(supplier.get());
            super.extractWidgetRenderState(g, mouseX, mouseY, a);
        }
    }

    // ------------------------------------------------------------------ buttons

    public enum Style { FILLED, TINTED, PLAIN, GREY }

    /** Rounded button. FILLED = solid colour, TINTED = translucent colour with coloured text, PLAIN = text only. */
    public static class Button extends Widget {
        private Component label;
        private int color;
        private Style style;
        private int radius;
        private boolean enabled = true;

        public Button(int width, int height, Component label, Style style, int color, Runnable action) {
            super(width, height);
            this.label = label;
            this.style = style;
            this.color = color;
            this.radius = Math.min(8, height / 2);
            onClick(action);
        }

        public Button radius(int r) {
            this.radius = r;
            return this;
        }

        public void set(Component label, Style style, int color) {
            this.label = label;
            this.style = style;
            this.color = color;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            this.active = enabled && onClick != null;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            boolean hover = isHovered() && enabled;
            int textColor;
            switch (style) {
                case FILLED -> {
                    roundRect(g, getX(), getY(), width, height, radius, enabled ? (hover ? shade(color, 1.15f) : color) : SURFACE_3);
                    textColor = enabled ? onColor(color) : TEXT_3;
                }
                case TINTED -> {
                    roundRect(g, getX(), getY(), width, height, radius, withAlpha(color, hover ? 0x55 : 0x33));
                    textColor = enabled ? color : TEXT_3;
                }
                case GREY -> {
                    roundRect(g, getX(), getY(), width, height, radius, hover ? SURFACE_3 : SURFACE_2);
                    textColor = enabled ? color : TEXT_3;
                }
                default -> textColor = enabled ? (hover ? shade(color, 1.25f) : color) : TEXT_3;
            }
            int tw = font().width(label);
            g.text(font(), label, getX() + (width - tw) / 2, getY() + (height - 8) / 2, textColor, false);
        }
    }

    /** Segmented control (tabs). */
    public static class Segmented extends Widget {
        private final List<Component> options;
        private final int selected;
        private final IntConsumer onSelect;

        public Segmented(int width, List<Component> options, int selected, IntConsumer onSelect) {
            super(width, 16);
            this.options = options;
            this.selected = selected;
            this.onSelect = onSelect;
            this.active = true;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            int i = (int) ((event.x() - getX()) * options.size() / width);
            if (i >= 0 && i < options.size() && i != selected) onSelect.accept(i);
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            click();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            roundRect(g, getX(), getY(), width, height, 6, SURFACE_2);
            int seg = width / options.size();
            for (int i = 0; i < options.size(); i++) {
                int sx = getX() + i * seg;
                int sw = i == options.size() - 1 ? width - i * seg : seg;
                if (i == selected) roundRect(g, sx + 1, getY() + 1, sw - 2, height - 2, 5, SURFACE_3);
                String raw = options.get(i).getString();
                float sc = font().width(raw) > sw - 4 ? 0.8f : 1f;
                String s = ellipsize(raw, (int) ((sw - 4) / sc));
                int tw = (int) (font().width(s) * sc);
                g.pose().pushMatrix();
                g.pose().translate(sx + (sw - tw) / 2f, getY() + (sc < 1 ? 5f : 4f));
                g.pose().scale(sc, sc);
                g.text(font(), s, 0, 0, i == selected ? TEXT : TEXT_2, false);
                g.pose().popMatrix();
            }
        }
    }

    /** iOS-style switch with a label on the left, as a full-width row. */
    public static class ToggleRow extends Widget {
        private final Component label;
        private final boolean on;

        public ToggleRow(int width, Component label, boolean on, Runnable toggle) {
            super(width, 22);
            this.label = label;
            this.on = on;
            onClick(toggle);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            roundRect(g, getX(), getY(), width, height, 7, isHovered() ? SURFACE_2 : SURFACE);
            g.text(font(), label, getX() + 8, getY() + 7, TEXT, false);
            int sw = 22, sh = 13, sx = getX() + width - sw - 6, sy = getY() + (height - sh) / 2;
            roundRect(g, sx, sy, sw, sh, sh / 2, on ? GREEN : SURFACE_3);
            int knob = on ? sx + sw - sh + 1 : sx + 1;
            circle(g, knob + (sh - 2) / 2, sy + sh / 2, (sh - 2) / 2, 0xFFFFFFFF);
        }
    }

    /** App icon with label and a red badge. */
    public static class AppTile extends Widget {
        private final Identifier icon;
        private final Component name;
        private final int badge;
        private final int iconSize;
        private final boolean showLabel;

        public AppTile(Identifier icon, Component name, int badge, int iconSize, boolean showLabel, Runnable open) {
            this(Math.max(iconSize + 6, 40), icon, name, badge, iconSize, showLabel, open);
        }

        public AppTile(int width, Identifier icon, Component name, int badge, int iconSize, boolean showLabel, Runnable open) {
            super(width, iconSize + (showLabel ? 13 : 2));
            this.icon = icon;
            this.name = name;
            this.badge = badge;
            this.iconSize = iconSize;
            this.showLabel = showLabel;
            onClick(open);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            int ix = getX() + (width - iconSize) / 2;
            int iy = getY() + 1;
            g.blit(RenderPipelines.GUI_TEXTURED, icon, ix, iy, 0, 0, iconSize, iconSize, 64, 64, 64, 64);
            if (isHovered()) roundRect(g, ix, iy, iconSize, iconSize, iconSize / 4, 0x33000000);
            if (badge > 0) {
                String count = badge > 99 ? "99+" : Integer.toString(badge);
                int bw = Math.max(11, font().width(count) + 6);
                int bx = ix + iconSize - bw + 4, by = iy - 3;
                roundRect(g, bx, by, bw, 11, 5, RED);
                g.text(font(), count, bx + (bw - font().width(count)) / 2, by + 2, 0xFFFFFFFF, false);
            }
            if (showLabel) {
                String s = ellipsize(name.getString(), (int) ((width + 4) / 0.8f));
                int tw = font().width(s);
                g.pose().pushMatrix();
                g.pose().translate(getX() + width / 2f, iy + iconSize + 3);
                g.pose().scale(0.8f, 0.8f);
                g.text(font(), s, -tw / 2, 0, WHITE, true);
                g.pose().popMatrix();
            }
        }
    }

    // ------------------------------------------------------------------ scroll list

    /** One entry in a {@link ScrollList}. Rows measure and draw themselves at the width they are given. */
    public interface Row {
        int height(int width);

        /** {@code mx/my} are relative to the row's top-left, or -1 when the mouse is elsewhere. */
        void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my);

        default boolean clickable() {
            return false;
        }

        /** {@code mx/my} are relative to the row's top-left. */
        default void click(int mx, int my, int width) {}

        default Component tooltip() {
            return null;
        }
    }

    /** Vertically scrolling list of rows, clipped to its own bounds. */
    public static class ScrollList extends Widget {
        private final List<Row> rows = new ArrayList<>();
        private final int gap;
        private double scroll;
        private int contentHeight;
        private Runnable onScroll;

        public ScrollList(int width, int height, int gap) {
            super(width, height);
            this.gap = gap;
            this.active = true;
        }

        public void setHeightTo(int height) {
            this.height = Math.max(10, height);
            clampScroll();
        }

        public ScrollList rows(List<Row> newRows) {
            rows.clear();
            rows.addAll(newRows);
            int rowWidth = rowWidth();
            int h = 0;
            for (Row r : rows) h += r.height(rowWidth) + gap;
            contentHeight = Math.max(0, h - gap);
            clampScroll();
            return this;
        }

        public double scroll() {
            return scroll;
        }

        public void setScroll(double value) {
            scroll = value;
            clampScroll();
        }

        public void onScroll(Runnable r) {
            this.onScroll = r;
        }

        public void scrollToBottom() {
            scroll = Math.max(0, contentHeight - height);
        }

        public boolean atBottom() {
            return scroll >= contentHeight - height - 2;
        }

        private int rowWidth() {
            return width - 3; // room for the scrollbar
        }

        private void clampScroll() {
            scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - height));
        }

        @Override
        public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
            if (!isMouseOver(x, y)) return false;
            scroll -= scrollY * 16;
            clampScroll();
            if (onScroll != null) onScroll.run();
            return true;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            int w = rowWidth();
            double y = getY() - scroll;
            for (Row r : rows) {
                int h = r.height(w);
                if (event.y() >= y && event.y() < y + h && event.x() < getX() + w) {
                    if (r.clickable()) {
                        click();
                        r.click((int) (event.x() - getX()), (int) (event.y() - y), w);
                    }
                    return;
                }
                y += h + gap;
            }
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            // rows decide (see onClick)
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            int w = rowWidth();
            g.enableScissor(getX(), getY(), getX() + width, getY() + height);
            int y = getY() - (int) Math.round(scroll);
            Row hovered = null;
            for (Row r : rows) {
                int h = r.height(w);
                if (y + h >= getY() && y <= getY() + height) {
                    boolean over = isHovered() && mouseY >= y && mouseY < y + h && mouseX < getX() + w;
                    if (over) hovered = r;
                    r.draw(g, getX(), y, w, over, over ? mouseX - getX() : -1, over ? mouseY - y : -1);
                }
                y += h + gap;
            }
            g.disableScissor();
            if (contentHeight > height) {
                int barH = Math.max(12, height * height / contentHeight);
                int barY = getY() + (int) ((height - barH) * (scroll / (contentHeight - height)));
                roundRect(g, getX() + width - 2, barY, 2, barH, 1, light ? 0x66000000 : 0x88FFFFFF);
            }
            if (hovered != null && hovered.tooltip() != null) {
                g.setTooltipForNextFrame(font(), hovered.tooltip(), mouseX, mouseY);
            }
        }
    }

    // ------------------------------------------------------------------ ready-made rows

    /** Simple wrapped text row. */
    public static Row textRow(Component text, int color) {
        return new Row() {
            public int height(int width) {
                return Math.max(9, font().split(text, width).size() * 10 - 1);
            }

            public void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my) {
                for (FormattedCharSequence line : font().split(text, width)) {
                    g.text(font(), line, x, y, color, false);
                    y += 10;
                }
            }
        };
    }

    /** Section heading inside a list. */
    public static Row headerRow(Component text) {
        return new Row() {
            public int height(int width) {
                return 14;
            }

            public void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my) {
                g.text(font(), text.copy().withStyle(net.minecraft.ChatFormatting.BOLD), x + 2, y + 4, TEXT_2, false);
            }
        };
    }

    /** A small inline action button inside a row: label, colour, and its rectangle relative to the row. */
    public record RowButton(Component label, int color, Runnable action) {}

    /**
     * Standard list row: optional leading painter (avatar/icon), title + subtitle, optional right text,
     * and up to two small action buttons on the right.
     */
    public static class ListRow implements Row {
        public interface Leading {
            void paint(GuiGraphicsExtractor g, int x, int y, int size);
        }

        private final Leading leading;
        private final Component title, subtitle, right;
        private final int rightColor, height;
        private final Runnable onClick;
        private final List<RowButton> buttons = new ArrayList<>();
        private Component tooltip;
        private Integer badge;

        public ListRow(Leading leading, Component title, Component subtitle, Component right, int rightColor, Runnable onClick) {
            this.leading = leading;
            this.title = title;
            this.subtitle = subtitle;
            this.right = right;
            this.rightColor = rightColor;
            this.onClick = onClick;
            this.height = subtitle == null ? 22 : 28;
        }

        public ListRow button(Component label, int color, Runnable action) {
            buttons.add(new RowButton(label, color, action));
            return this;
        }

        public ListRow tooltip(Component t) {
            this.tooltip = t;
            return this;
        }

        public ListRow badge(int count) {
            this.badge = count > 0 ? count : null;
            return this;
        }

        public int height(int width) {
            return height;
        }

        private int[] buttonBounds(int i, int width) {
            int bx = width - 4;
            for (int j = buttons.size() - 1; j >= i; j--) {
                int bw = font().width(buttons.get(j).label()) + 10;
                bx -= bw;
                if (j == i) return new int[]{bx, (height - 14) / 2, bw, 14};
                bx -= 3;
            }
            return new int[]{0, 0, 0, 0};
        }

        public void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my) {
            boolean overButton = false;
            for (int i = 0; i < buttons.size(); i++) {
                int[] b = buttonBounds(i, width);
                boolean over = mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
                overButton |= over;
            }
            roundRect(g, x, y, width, height, 7, hovered && onClick != null && !overButton ? SURFACE_2 : SURFACE);
            int tx = x + 6;
            if (leading != null) {
                int size = height - 8;
                leading.paint(g, x + 4, y + 4, size);
                tx = x + 4 + size + 6;
            }
            int textRight = x + width - 6;
            if (!buttons.isEmpty()) textRight = x + buttonBounds(0, width)[0] - 4;
            if (right != null) {
                int rw = font().width(right);
                g.text(font(), right, textRight - rw, y + (height - 8) / 2, rightColor, false);
                textRight -= rw + 4;
            }
            if (badge != null) {
                String s = Integer.toString(badge);
                int bw = Math.max(11, font().width(s) + 6);
                roundRect(g, textRight - bw, y + (height - 11) / 2, bw, 11, 5, RED);
                g.text(font(), s, textRight - bw + (bw - font().width(s)) / 2, y + (height - 11) / 2 + 2, 0xFFFFFFFF, false);
                textRight -= bw + 4;
            }
            int maxText = Math.max(10, textRight - tx);
            if (subtitle == null) {
                g.text(font(), ellipsize(title.getString(), maxText), tx, y + 7, TEXT, false);
            } else {
                g.text(font(), ellipsize(title.getString(), maxText), tx, y + 5, TEXT, false);
                g.pose().pushMatrix();
                g.pose().translate(tx, y + 16);
                g.pose().scale(0.85f, 0.85f);
                g.text(font(), ellipsize(subtitle.getString(), (int) (maxText / 0.85f)), 0, 0, TEXT_2, false);
                g.pose().popMatrix();
            }
            for (int i = 0; i < buttons.size(); i++) {
                RowButton b = buttons.get(i);
                int[] r = buttonBounds(i, width);
                boolean over = mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
                roundRect(g, x + r[0], y + r[1], r[2], r[3], 7, withAlpha(b.color(), over ? 0x66 : 0x33));
                g.text(font(), b.label(), x + r[0] + 5, y + r[1] + 3, b.color(), false);
            }
        }

        public boolean clickable() {
            return onClick != null || !buttons.isEmpty();
        }

        public void click(int mx, int my, int width) {
            for (int i = 0; i < buttons.size(); i++) {
                int[] r = buttonBounds(i, width);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    buttons.get(i).action().run();
                    return;
                }
            }
            if (onClick != null) onClick.run();
        }

        public Component tooltip() {
            return tooltip;
        }
    }

    /** Leading avatar painter for {@link ListRow}. */
    public static ListRow.Leading avatarOf(String name, boolean group) {
        return (g, x, y, size) -> avatar(g, x, y, size, name, group);
    }
}
