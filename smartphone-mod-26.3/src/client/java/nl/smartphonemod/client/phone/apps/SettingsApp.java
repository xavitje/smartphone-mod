package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.client.ClientConfig;
import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.registry.ModSounds;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Settings: wallpaper, sounds & notifications, clock format, location privacy and friends. */
public class SettingsApp extends PhoneApp {
    private boolean requested;

    @Override
    public String id() {
        return "settings";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.app.settings"), null);
        if (!requested) {
            requested = true;
            ClientPlayNetworking.send(C2S.MapRequest.INSTANCE);
        }
        ClientConfig cfg = ClientConfig.get();
        UiKit.ScrollList list = list("settings", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();

        rows.add(UiKit.headerRow(Component.translatable("smartphone.settings.appearance")));
        rows.add(segmentRow(new String[]{"smartphone.settings.theme.dark", "smartphone.settings.theme.light", "smartphone.settings.theme.auto"},
                () -> cfg.theme, i -> {
                    cfg.theme = i;
                    ClientConfig.save();
                    ClientConfig.updateTheme();
                    phone.refresh();
                }));
        rows.add(UiKit.textRow(Component.translatable("smartphone.settings.theme.hint"), UiKit.TEXT_3));
        rows.add(UiKit.headerRow(Component.translatable("smartphone.settings.wallpaper")));
        rows.add(wallpaperRow());

        rows.add(UiKit.headerRow(Component.translatable("smartphone.settings.sounds")));
        rows.add(toggle("smartphone.settings.ringtone", cfg.ringtone, v -> cfg.ringtone = v));
        if (cfg.ringtone) {
            for (String tone : ModSounds.RINGTONES) rows.add(ringtoneRow(cfg, tone));
        }
        rows.add(toggle("smartphone.settings.vibrate", cfg.vibrate, v -> cfg.vibrate = v));
        rows.add(toggle("smartphone.settings.popups", cfg.popups, v -> cfg.popups = v));
        rows.add(toggle("smartphone.settings.previews", cfg.previews, v -> cfg.previews = v));
        rows.add(toggle("smartphone.settings.clock24", cfg.use24h, v -> cfg.use24h = v));

        rows.add(UiKit.headerRow(Component.translatable("smartphone.settings.location")));
        rows.add(privacyRow());
        rows.add(UiKit.textRow(Component.translatable("smartphone.settings.location_hint"), UiKit.TEXT_3));

        rows.add(UiKit.headerRow(Component.translatable("smartphone.settings.friends")));
        for (S2C.FriendEntry f : ClientPhoneState.friends) {
            rows.add(new UiKit.ListRow(UiKit.avatarOf(f.name(), false), Component.literal(f.name()), null, null, 0, null)
                    .button(Component.translatable("smartphone.settings.remove"), UiKit.RED,
                            () -> ClientPlayNetworking.send(new C2S.FriendSet(f.uuid(), false))));
        }
        Predicate<java.util.UUID> notFriend = u -> ClientPhoneState.friends.stream().noneMatch(f -> f.uuid().equals(u));
        rows.add(new UiKit.ListRow((g, x, y, size) -> UiKit.textCentered(g, Component.literal("+"), x + size / 2, y + (size - 8) / 2, UiKit.BLUE),
                Component.translatable("smartphone.settings.add_friend").withColor(UiKit.BLUE), null, null, 0,
                () -> phone.open(new ContactPickerApp(Component.translatable("smartphone.settings.add_friend"), false, notFriend,
                        (conv, name) -> ClientPlayNetworking.send(new C2S.FriendSet(conv.player(), true))))));

        rows.add(UiKit.headerRow(Component.translatable("smartphone.settings.server")));
        var info = ClientPhoneState.serverInfo;
        rows.add(UiKit.textRow(Component.translatable("smartphone.settings.server_info",
                fmt(info.transferFeePercent()), info.transferFeeMinimum(), fmt(info.marketFeePercent()), fmt(info.dailyInterestPercent())), UiKit.TEXT_2));
        list.rows(rows);
    }

    private static String fmt(double pct) {
        return pct == Math.rint(pct) ? Long.toString((long) pct) : String.format("%.2f", pct);
    }

    private UiKit.Row toggle(String key, boolean on, Consumer<Boolean> set) {
        return new UiKit.Row() {
            public int height(int width) {
                return 22;
            }

            public void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my) {
                UiKit.roundRect(g, x, y, width, 22, 7, hovered ? UiKit.SURFACE_2 : UiKit.SURFACE);
                g.text(font, UiKit.ellipsize(Component.translatable(key).getString(), width - 44), x + 8, y + 7, UiKit.TEXT, false);
                int sw = 22, sh = 13, sx = x + width - sw - 6, sy = y + 5;
                UiKit.roundRect(g, sx, sy, sw, sh, sh / 2, on ? UiKit.GREEN : UiKit.SURFACE_3);
                int knob = on ? sx + sw - sh + 1 : sx + 1;
                UiKit.circle(g, knob + (sh - 2) / 2, sy + sh / 2, (sh - 2) / 2, 0xFFFFFFFF);
            }

            public boolean clickable() {
                return true;
            }

            public void click(int mx, int my, int width) {
                set.accept(!on);
                ClientConfig.save();
                phone.refresh();
            }
        };
    }

    private UiKit.Row ringtoneRow(ClientConfig cfg, String tone) {
        boolean selected = tone.equals(cfg.ringtoneName);
        return new UiKit.ListRow((g, x, y, size) -> UiKit.textCentered(g, Component.literal(selected ? "\u2713" : "\u266A"),
                x + size / 2, y + (size - 8) / 2, selected ? UiKit.BLUE : UiKit.TEXT_3),
                Component.translatable("smartphone.ringtone." + tone), null, null, 0, () -> {
                    cfg.ringtoneName = tone;
                    ClientConfig.save();
                    mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(ModSounds.ringtone(tone), 1.0f, 0.9f));
                    phone.refresh();
                });
    }

    /** A row of equally wide choices (used for the theme). */
    private UiKit.Row segmentRow(String[] keys, java.util.function.IntSupplier selected, java.util.function.IntConsumer onSelect) {
        return new UiKit.Row() {
            public int height(int width) {
                return 18;
            }

            public void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my) {
                UiKit.roundRect(g, x, y, width, 18, 6, UiKit.SURFACE_2);
                int seg = width / keys.length;
                for (int i = 0; i < keys.length; i++) {
                    int sx = x + i * seg, sw = i == keys.length - 1 ? width - i * seg : seg;
                    boolean sel = selected.getAsInt() == i;
                    if (sel) UiKit.roundRect(g, sx + 1, y + 1, sw - 2, 16, 5, UiKit.isLight() ? UiKit.WHITE : UiKit.SURFACE_3);
                    String s = UiKit.ellipsize(Component.translatable(keys[i]).getString(), sw - 4);
                    g.text(font, s, sx + (sw - font.width(s)) / 2, y + 5, sel ? UiKit.TEXT : UiKit.TEXT_2, false);
                }
            }

            public boolean clickable() {
                return true;
            }

            public void click(int mx, int my, int width) {
                int i = Math.min(keys.length - 1, mx * keys.length / Math.max(1, width));
                if (i != selected.getAsInt()) onSelect.accept(i);
            }
        };
    }

    private UiKit.Row privacyRow() {
        String[] keys = {"smartphone.privacy.nobody", "smartphone.privacy.friends", "smartphone.privacy.everyone"};
        return new UiKit.Row() {
            public int height(int width) {
                return 18;
            }

            public void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my) {
                UiKit.roundRect(g, x, y, width, 18, 6, UiKit.SURFACE_2);
                int seg = width / 3;
                for (int i = 0; i < 3; i++) {
                    int sx = x + i * seg, sw = i == 2 ? width - 2 * seg : seg;
                    boolean sel = ClientPhoneState.gpsMode == i;
                    if (sel) UiKit.roundRect(g, sx + 1, y + 1, sw - 2, 16, 5, UiKit.SURFACE_3);
                    String s = UiKit.ellipsize(Component.translatable(keys[i]).getString(), sw - 4);
                    g.text(font, s, sx + (sw - font.width(s)) / 2, y + 5, sel ? UiKit.TEXT : UiKit.TEXT_2, false);
                }
            }

            public boolean clickable() {
                return true;
            }

            public void click(int mx, int my, int width) {
                int mode = Math.min(2, mx * 3 / Math.max(1, width));
                if (mode != ClientPhoneState.gpsMode) {
                    ClientPhoneState.gpsMode = mode;
                    ClientPlayNetworking.send(new C2S.GpsMode(mode));
                    phone.refresh();
                }
            }
        };
    }

    private UiKit.Row wallpaperRow() {
        String[] names = ClientConfig.WALLPAPERS;
        return new UiKit.Row() {
            private int cell(int width) {
                return (width - (names.length - 1) * 4) / names.length;
            }

            public int height(int width) {
                return (int) (cell(width) * 1.9f);
            }

            public void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my) {
                int c = cell(width), ch = height(width);
                for (int i = 0; i < names.length; i++) {
                    int px = x + i * (c + 4);
                    boolean sel = names[i].equals(ClientConfig.get().wallpaper);
                    if (sel) UiKit.roundRect(g, px - 1, y - 1, c + 2, ch + 2, 6, UiKit.BLUE);
                    g.blit(RenderPipelines.GUI_TEXTURED, SmartphoneMod.id("textures/gui/wallpaper/" + names[i] + ".png"),
                            px + (sel ? 1 : 0), y + (sel ? 1 : 0), 0, 0, c - (sel ? 2 : 0), ch - (sel ? 2 : 0), 180, 380, 180, 380);
                    UiKit.cornerMask(g, px + (sel ? 1 : 0), y + (sel ? 1 : 0), c - (sel ? 2 : 0), ch - (sel ? 2 : 0), 5, sel ? UiKit.BLUE : UiKit.BG);
                }
            }

            public boolean clickable() {
                return true;
            }

            public void click(int mx, int my, int width) {
                int i = mx / (cell(width) + 4);
                if (i >= 0 && i < names.length) {
                    ClientConfig.get().wallpaper = names[i];
                    ClientConfig.save();
                    phone.refresh();
                }
            }
        };
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.MAP || update == PhoneScreen.Update.CONTACTS) phone.refresh();
    }
}
