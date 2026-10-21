package nl.smartphonemod.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Text input in the phone's style: rounded dark field with a blue outline when focused. */
public class PhoneField extends EditBox {
    private final String key;
    private Runnable onEnter;
    private boolean suggestPlayers;
    private java.util.function.Consumer<String> onPick;

    public PhoneField(Font font, String key, int width, int height, Component hint) {
        super(font, width, height, hint);
        this.key = key;
        setHint(hint.copy().withColor(UiKit.TEXT_3));
        setTextColor(UiKit.TEXT);
        setTextShadow(!UiKit.isLight()); // a dark shadow looks smudgy on the light theme
    }

    public String key() {
        return key;
    }

    public PhoneField onEnter(Runnable action) {
        this.onEnter = action;
        return this;
    }

    /** Shows a searchable dropdown of (online) players while this field is focused. */
    public PhoneField suggestPlayers(java.util.function.Consumer<String> onPick) {
        this.suggestPlayers = true;
        this.onPick = onPick;
        return this;
    }

    public boolean hasSuggestions() {
        return suggestPlayers;
    }

    public void pickedSuggestion() {
        if (onPick != null) onPick.accept(getValue());
    }

    public boolean enter() {
        if (onEnter == null) return false;
        onEnter.run();
        return true;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (!isVisible()) return;
        UiKit.roundRect(g, getX(), getY(), getWidth(), getHeight(), 7, isFocused() ? UiKit.BLUE : UiKit.SURFACE_3);
        UiKit.roundRect(g, getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 2, 6, UiKit.SURFACE_2);
        // Let vanilla draw text, cursor and selection, without its own sprite frame.
        // Unbordered EditBoxes draw at their top-left corner, so temporarily move the box to where the text belongs.
        int x = getX(), y = getY(), w = getWidth();
        setBordered(false);
        setX(x + 5);
        setY(y + (getHeight() - 8) / 2);
        setWidth(w - 10);
        g.enableScissor(x + 2, y, x + w - 3, y + getHeight());
        try {
            super.extractWidgetRenderState(g, mouseX, mouseY, a);
        } finally {
            g.disableScissor();
            setX(x);
            setY(y);
            setWidth(w);
            setBordered(true);
        }
    }
}
