package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.ui.UiKit;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** A classic phone calculator (immediate execution, like the one on your real phone). */
public class CalculatorApp extends PhoneApp {
    private String display = "0";
    private BigDecimal stored;
    private char op;
    private boolean fresh = true;
    private boolean error;
    /** Key last pressed on the keyboard, flashed on screen for a moment. */
    private String flashKey;
    private long flashAt;

    @Override
    public String id() {
        return "calculator";
    }

    @Override
    protected void build(LinearLayout col) {
        int gap = 4;
        int key = (w - 3 * gap) / 4;
        int keypad = key * 5 + gap * 4;
        String shown = error ? Component.translatable("smartphone.calc.error").getString() : group(display);
        float scale = Math.max(1.2f, Math.min(3f, (w - 6) / (float) Math.max(1, font.width(shown))));
        UiKit.Label label = new UiKit.Label(Component.literal(shown), UiKit.TEXT, w).scale(scale);
        int spacer = Math.max(0, h - keypad - label.getHeight() - 8);
        col.addChild(net.minecraft.client.gui.layouts.SpacerElement.height(spacer));
        // Right-aligned display
        LinearLayout disp = LinearLayout.horizontal();
        disp.addChild(net.minecraft.client.gui.layouts.SpacerElement.width(Math.max(0, w - label.getWidth())));
        disp.addChild(label);
        col.addChild(disp, s -> s.paddingBottom(4));

        String[][] layout = {
                {"AC", "±", "%", "÷"},
                {"7", "8", "9", "×"},
                {"4", "5", "6", "−"},
                {"1", "2", "3", "+"},
                {"0", ".", "="}};
        for (String[] row : layout) {
            LinearLayout r = LinearLayout.horizontal().spacing(gap);
            for (String k : row) {
                boolean wide = k.equals("0");
                int color;
                UiKit.Style style;
                if ("÷×−+=".contains(k)) {
                    style = UiKit.Style.FILLED;
                    color = op != 0 && fresh && k.charAt(0) == op ? UiKit.shade(UiKit.ORANGE, 1.4f) : UiKit.ORANGE;
                } else if (k.equals("AC") || k.equals("±") || k.equals("%")) {
                    style = UiKit.Style.FILLED;
                    color = UiKit.isLight() ? 0xFFC7C7CC : 0xFFA5A5A5;
                } else {
                    style = UiKit.Style.FILLED;
                    color = UiKit.isLight() ? 0xFFE3E3E8 : 0xFF333333;
                }
                if (k.equals(flashKey) && System.currentTimeMillis() - flashAt < 150) color = UiKit.shade(color, 1.45f);
                r.addChild(new UiKit.Button(wide ? key * 2 + gap : key, key, Component.literal(k), style, color, () -> press(k)).radius(key / 2));
            }
            col.addChild(r);
        }
    }

    // ------------------------------------------------------------------ keyboard

    @Override
    protected boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        String k = switch (event.codepoint()) {
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> Character.toString(event.codepoint());
            case '.', ',' -> ".";
            case '+' -> "+";
            case '-' -> "\u2212";
            case '*', 'x', 'X' -> "\u00D7";
            case '/', ':' -> "\u00F7";
            case '%' -> "%";
            case '=' -> "=";
            case 'c', 'C' -> "AC";
            default -> null;
        };
        if (k == null) return false;
        keyboard(k);
        return true;
    }

    @Override
    protected boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) {
            keyboard("=");
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE) {
            backspace();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_DELETE) {
            keyboard("AC");
            return true;
        }
        return false;
    }

    private void keyboard(String k) {
        flashKey = k;
        flashAt = System.currentTimeMillis();
        UiKit.click();
        press(k);
    }

    private void backspace() {
        if (error || fresh) return;
        display = display.length() <= 1 || (display.length() == 2 && display.startsWith("-")) ? "0" : display.substring(0, display.length() - 1);
        if (display.equals("0")) fresh = true;
        phone.refresh();
    }

    @Override
    protected void tick() {
        if (flashKey != null && System.currentTimeMillis() - flashAt >= 150) {
            flashKey = null;
            phone.refresh();
        }
    }

    private static String group(String s) {
        try {
            if (s.contains("E") || s.endsWith(".")) return s;
            BigDecimal d = new BigDecimal(s);
            String plain = d.toPlainString();
            int dot = plain.indexOf('.');
            String intPart = dot < 0 ? plain : plain.substring(0, dot);
            String frac = dot < 0 ? "" : plain.substring(dot);
            boolean neg = intPart.startsWith("-");
            if (neg) intPart = intPart.substring(1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < intPart.length(); i++) {
                if (i > 0 && (intPart.length() - i) % 3 == 0) sb.append(' ');
                sb.append(intPart.charAt(i));
            }
            return (neg ? "-" : "") + sb + frac;
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private void press(String k) {
        if (error && !k.equals("AC")) return;
        switch (k) {
            case "AC" -> {
                display = "0";
                stored = null;
                op = 0;
                fresh = true;
                error = false;
            }
            case "±" -> display = display.startsWith("-") ? display.substring(1) : display.equals("0") ? "0" : "-" + display;
            case "%" -> display = format(new BigDecimal(display).divide(BigDecimal.valueOf(100), MathContext.DECIMAL64));
            case "." -> {
                if (fresh) {
                    display = "0.";
                    fresh = false;
                } else if (!display.contains(".")) display += ".";
            }
            case "=" -> {
                if (op != 0 && stored != null) {
                    compute();
                    op = 0;
                    stored = null;
                    fresh = true;
                }
            }
            case "÷", "×", "−", "+" -> {
                if (op != 0 && stored != null && !fresh) compute();
                stored = new BigDecimal(display);
                op = k.charAt(0);
                fresh = true;
            }
            default -> { // digit
                if (fresh || display.equals("0")) {
                    display = k;
                    fresh = false;
                } else if (display.replace("-", "").replace(".", "").length() < 12) {
                    display += k;
                }
            }
        }
        phone.refresh();
    }

    private void compute() {
        BigDecimal b = new BigDecimal(display);
        BigDecimal r;
        switch (op) {
            case '+' -> r = stored.add(b);
            case '−' -> r = stored.subtract(b);
            case '×' -> r = stored.multiply(b);
            default -> {
                if (b.signum() == 0) {
                    error = true;
                    return;
                }
                r = stored.divide(b, MathContext.DECIMAL64);
            }
        }
        display = format(r);
    }

    private static String format(BigDecimal d) {
        BigDecimal r = d.round(new MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros();
        String s = r.abs().compareTo(new BigDecimal("1e12")) >= 0 ? r.toString() : r.toPlainString();
        return s.equals("-0") ? "0" : s;
    }
}
