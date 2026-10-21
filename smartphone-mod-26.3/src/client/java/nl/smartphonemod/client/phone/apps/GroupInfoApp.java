package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.Conv;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Group details: members, add someone, leave. */
public class GroupInfoApp extends PhoneApp {
    private final long group;
    private final String name;
    private boolean confirmLeave;

    public GroupInfoApp(long group, String name) {
        this.group = group;
        this.name = name;
    }

    @Override
    public String id() {
        return "messages";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.literal(name), null);
        LinearLayout actions = LinearLayout.horizontal().spacing(4);
        int bw = (w - 4) / 2;
        actions.addChild(new UiKit.Button(bw, 18, Component.translatable("smartphone.messages.add_member"), UiKit.Style.TINTED, UiKit.BLUE,
                () -> phone.open(new ContactPickerApp(Component.translatable("smartphone.messages.add_member"), false, null,
                        (conv, n) -> ClientPlayNetworking.send(new C2S.GroupMembership(group, conv.player(), true))))));
        actions.addChild(new UiKit.Button(bw, 18, Component.translatable(confirmLeave ? "smartphone.messages.leave_confirm"
                : "smartphone.messages.leave"), confirmLeave ? UiKit.Style.FILLED : UiKit.Style.TINTED, UiKit.RED, this::leave));
        col.addChild(actions);
        col.addChild(new UiKit.Label(Component.translatable("smartphone.messages.members_title"), UiKit.TEXT_2, w));
        UiKit.ScrollList list = list("members", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        var thread = ClientPhoneState.thread;
        if (thread != null && thread.conv().equals(Conv.group(group))) {
            for (String member : thread.members()) {
                rows.add(new UiKit.ListRow(UiKit.avatarOf(member, false), Component.literal(member), null, null, 0, null));
            }
        }
        list.rows(rows);
    }

    private void leave() {
        if (!confirmLeave) {
            confirmLeave = true;
            phone.refresh();
            return;
        }
        ClientPlayNetworking.send(new C2S.GroupMembership(group, Conv.NONE, false));
        phone.home();
        phone.open(new MessagesApp());
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.THREAD) phone.refresh();
    }
}
