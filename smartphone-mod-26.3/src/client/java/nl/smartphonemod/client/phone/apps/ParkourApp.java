package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parkour: all courses with your best time and the record; tap one for its leaderboard and to challenge a friend. */
public class ParkourApp extends PhoneApp {
    private boolean requested;

    @Override
    public String id() {
        return "parkour";
    }

    public static String time(long ms) {
        if (ms < 0) return "-";
        long m = ms / 60000, s = (ms / 1000) % 60, cs = (ms % 1000) / 10;
        return String.format(Locale.ROOT, "%d:%02d.%02d", m, s, cs);
    }

    static UiKit.ListRow.Leading icon(ItemStack stack) {
        return (g, x, y, size) -> {
            UiKit.roundRect(g, x, y, size, size, 5, UiKit.SURFACE_2);
            g.item(stack, x + (size - 16) / 2, y + (size - 16) / 2);
        };
    }

    /** Pending race invite, shown at the top of both parkour screens. */
    static void inviteBanner(LinearLayout col, int w) {
        S2C.ParkourCourses data = ClientPhoneState.parkour;
        if (data == null || data.inviteFrom().isEmpty()) return;
        col.addChild(new UiKit.Label(Component.translatable("smartphone.parkour.race.invite_short", data.inviteFrom(), data.inviteCourse()),
                UiKit.ORANGE, w));
        LinearLayout row = LinearLayout.horizontal().spacing(4);
        int bw = (w - 4) / 2;
        row.addChild(new UiKit.Button(bw, 18, Component.translatable("smartphone.parkour.race.accept_button"), UiKit.Style.FILLED, UiKit.GREEN,
                () -> ClientPlayNetworking.send(new C2S.ParkourRespond(true))));
        row.addChild(new UiKit.Button(bw, 18, Component.translatable("smartphone.parkour.race.deny_button"), UiKit.Style.GREY, UiKit.RED,
                () -> ClientPlayNetworking.send(new C2S.ParkourRespond(false))));
        col.addChild(row, s -> s.paddingBottom(4));
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.app.parkour"), null);
        if (!requested) {
            requested = true;
            ClientPlayNetworking.send(C2S.ParkourRequest.INSTANCE);
        }
        S2C.ParkourCourses data = ClientPhoneState.parkour;
        if (data == null) {
            col.addChild(new UiKit.Label(Component.translatable("smartphone.loading"), UiKit.TEXT_3, w));
            return;
        }
        inviteBanner(col, w);
        col.addChild(new UiKit.Label(Component.translatable("smartphone.parkour.app.howto"), UiKit.TEXT_2, w).scale(0.85f));
        UiKit.ScrollList list = list("courses", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        if (data.courses().isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.parkour.app.none"), UiKit.TEXT_3));
        ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
        for (S2C.ParkourCourse c : data.courses()) {
            String record = c.recordMs() < 0 ? Component.translatable("smartphone.parkour.app.no_record").getString()
                    : "★ " + c.recordName() + " " + time(c.recordMs());
            Component sub = Component.literal(c.checkpoints() + " CP · " + record);
            Component right = Component.literal(c.myBest() < 0 ? "-" : (c.myRank() > 0 ? "#" + c.myRank() + " " : "") + time(c.myBest()));
            rows.add(new UiKit.ListRow(icon(boots), Component.literal(c.display()), sub, right,
                    c.myRank() == 1 ? UiKit.ORANGE : UiKit.TEXT_2, () -> phone.open(new ParkourCourseApp(c))));
        }
        list.rows(rows);
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.PARKOUR) phone.refresh();
    }
}
