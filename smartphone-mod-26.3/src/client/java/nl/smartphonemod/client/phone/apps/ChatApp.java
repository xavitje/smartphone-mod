package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.PhotoCache;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.Conv;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** One conversation, direct or group. Photos show inline and open full-size when tapped. */
public class ChatApp extends PhoneApp {
    private final Conv conv;
    private final String title;
    private boolean requested;
    private boolean scrollToBottom = true;

    public ChatApp(Conv conv, String title) {
        this.conv = conv;
        this.title = title;
    }

    public Conv conv() {
        return conv;
    }

    @Override
    public String id() {
        return "messages";
    }

    @Override
    protected void build(LinearLayout col) {
        S2C.ChatThread thread = ClientPhoneState.thread != null && ClientPhoneState.thread.conv().equals(conv)
                ? ClientPhoneState.thread : null;
        UiKit.Button info = conv.isGroup()
                ? new UiKit.Button(34, 14, Component.translatable("smartphone.messages.info"), UiKit.Style.TINTED, UiKit.BLUE,
                () -> phone.open(new GroupInfoApp(conv.group(), thread == null ? title : thread.title())))
                : null;
        header(col, Component.literal(thread == null ? title : thread.title()), info);
        Component subtitle;
        if (thread == null) subtitle = Component.translatable("smartphone.loading");
        else if (conv.isGroup()) subtitle = Component.literal(String.join(", ", thread.members()));
        else subtitle = Component.translatable(thread.online() ? "smartphone.messages.online" : "smartphone.messages.offline");
        col.addChild(new UiKit.Label(Component.literal(UiKit.ellipsize(subtitle.getString(), w)), UiKit.TEXT_2, w));

        if (!requested) {
            requested = true;
            ClientPlayNetworking.send(new C2S.OpenThread(conv));
        }

        UiKit.ScrollList list = list("thread", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        if (thread != null) {
            if (thread.messages().isEmpty()) {
                rows.add(UiKit.textRow(Component.translatable("smartphone.messages.say_hi", thread.title()), UiKit.TEXT_2));
            }
            if (!conv.isGroup() && !thread.online()) {
                rows.add(UiKit.textRow(Component.translatable("smartphone.messages.offline_hint"), UiKit.TEXT_3));
            }
            SimpleDateFormat time = new SimpleDateFormat("dd-MM HH:mm");
            String lastSender = "";
            for (S2C.MessageEntry m : thread.messages()) {
                boolean showName = conv.isGroup() && !m.mine() && !m.sender().equals(lastSender);
                lastSender = m.mine() ? "" : m.sender();
                rows.add(bubble(m, showName, Component.literal(time.format(new Date(m.timestamp())))));
            }
        }
        list.rows(rows);
        if (scrollToBottom) {
            phone.afterLayout(list::scrollToBottom);
            scrollToBottom = false;
        }
    }

    @Override
    protected void buildBottom(LinearLayout bottom) {
        LinearLayout input = LinearLayout.horizontal().spacing(3);
        input.addChild(new UiKit.Button(18, 18, Component.literal("+"), UiKit.Style.GREY, UiKit.BLUE,
                () -> phone.open(new PhotosApp(id -> ClientPlayNetworking.send(new C2S.SendMessage(conv, "", id))))));
        input.addChild(field("msg", w - 18 - 22 - 6, Component.translatable("smartphone.messages.placeholder"),
                ClientPhoneState.serverInfo.maxMessageLength(), this::send));
        input.addChild(new UiKit.Button(22, 18, Component.literal("↑"), UiKit.Style.FILLED, UiKit.BLUE, this::send).radius(9));
        bottom.addChild(input);
        if (focusKey == null) focusKey = "msg";
    }

    private void send() {
        String text = text("msg");
        if (text.isEmpty()) return;
        ClientPlayNetworking.send(new C2S.SendMessage(conv, text, 0));
        fields.put("msg", "");
        focusKey = "msg";
        scrollToBottom = true;
        phone.refresh();
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.THREAD) {
            scrollToBottom = true;
            phone.refresh();
        } else if (update == PhoneScreen.Update.PHOTO) {
            phone.refresh();
        }
    }

    /** Chat bubble (text or photo). Own messages are blue and right-aligned. */
    private UiKit.Row bubble(S2C.MessageEntry m, boolean showName, Component tooltip) {
        Component text = Component.literal(m.body());
        return new UiKit.Row() {
            private List<FormattedCharSequence> lines(int width) {
                return m.body().isEmpty() ? List.of() : font.split(text, (int) (width * 0.72) - 12);
            }

            private int photoW(int width) {
                return (int) (width * 0.62);
            }

            private int photoH(int width) {
                PhotoCache.Photo p = PhotoCache.get(m.photoId());
                int pw = photoW(width);
                return p == null ? pw * 9 / 16 : Math.min(pw * 4 / 3, pw * p.height() / Math.max(1, p.width()));
            }

            public int height(int width) {
                int h = (showName ? 10 : 0);
                if (m.photoId() > 0) h += photoH(width) + (m.body().isEmpty() ? 0 : 2);
                if (!m.body().isEmpty()) h += lines(width).size() * 10 + 6;
                return h;
            }

            public void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my) {
                if (showName) {
                    g.pose().pushMatrix();
                    g.pose().translate(x + 6, y + 1);
                    g.pose().scale(0.8f, 0.8f);
                    g.text(font, m.sender(), 0, 0, UiKit.avatarColor(m.sender()), false);
                    g.pose().popMatrix();
                    y += 10;
                }
                if (m.photoId() > 0) {
                    int pw = photoW(width), ph = photoH(width);
                    int px = m.mine() ? x + width - pw : x;
                    PhotoCache.Photo p = PhotoCache.get(m.photoId());
                    if (p != null) {
                        g.blit(RenderPipelines.GUI_TEXTURED, p.texture(), px, y, 0, 0, pw, ph, p.width(), p.height(), p.width(), p.height());
                        UiKit.cornerMask(g, px, y, pw, ph, 6, UiKit.BG);
                    } else {
                        UiKit.roundRect(g, px, y, pw, ph, 6, UiKit.SURFACE_2);
                        UiKit.textCentered(g, Component.translatable("smartphone.photos.loading"), px + pw / 2, y + ph / 2 - 4, UiKit.TEXT_3);
                    }
                    y += ph + 2;
                }
                List<FormattedCharSequence> ls = lines(width);
                if (ls.isEmpty()) return;
                int tw = 0;
                for (FormattedCharSequence l : ls) tw = Math.max(tw, font.width(l));
                int bw = tw + 12, bh = ls.size() * 10 + 6;
                int bx = m.mine() ? x + width - bw : x;
                UiKit.roundRect(g, bx, y, bw, bh, 8, m.mine() ? UiKit.BLUE : UiKit.SURFACE_2);
                int ty = y + 4;
                for (FormattedCharSequence l : ls) {
                    g.text(font, l, bx + 6, ty, m.mine() ? UiKit.WHITE : UiKit.TEXT, false);
                    ty += 10;
                }
            }

            public boolean clickable() {
                return m.photoId() > 0;
            }

            public void click(int mx, int my, int width) {
                phone.open(new PhotoViewerApp(m.photoId(), m.mine()));
            }

            public Component tooltip() {
                return tooltip;
            }
        };
    }
}
