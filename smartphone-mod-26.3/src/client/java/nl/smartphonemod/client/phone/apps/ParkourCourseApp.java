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

import java.util.ArrayList;
import java.util.List;

/** One course: your stats, challenge a player, and the leaderboard. */
public class ParkourCourseApp extends PhoneApp {
    private final S2C.ParkourCourse course;
    private boolean requested;

    public ParkourCourseApp(S2C.ParkourCourse course) {
        this.course = course;
    }

    @Override
    public String id() {
        return "parkour";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.literal(course.display()), null);
        if (!requested) {
            requested = true;
            ClientPhoneState.parkourBoard = null;
            ClientPlayNetworking.send(new C2S.ParkourBoardRequest(course.name()));
        }
        ParkourApp.inviteBanner(col, w);
        col.addChild(new UiKit.Label(Component.translatable("smartphone.parkour.app.start", course.x(), course.y(), course.z()),
                UiKit.TEXT_2, w).scale(0.85f));
        S2C.ParkourBoard board = ClientPhoneState.parkourBoard;
        if (board != null && board.course().equals(course.name())) {
            col.addChild(new UiKit.Label(Component.translatable("smartphone.parkour.app.mine", ParkourApp.time(board.myBest()),
                    board.myRank() > 0 ? "#" + board.myRank() : "-", board.myRuns(), board.myWins()), UiKit.TEXT, w));
        }
        // Challenge a friend.
        LinearLayout row = LinearLayout.horizontal().spacing(4);
        row.addChild(playerField("rival", w - 4 - 58, Component.translatable("smartphone.parkour.app.rival"), this::challenge));
        row.addChild(new UiKit.Button(58, 18, Component.translatable("smartphone.parkour.app.challenge"), UiKit.Style.FILLED, UiKit.ORANGE,
                this::challenge));
        col.addChild(row, s -> s.paddingBottom(3));

        UiKit.ScrollList list = list("board_" + course.name(), 2, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        if (board == null || !board.course().equals(course.name())) {
            rows.add(UiKit.textRow(Component.translatable("smartphone.loading"), UiKit.TEXT_3));
        } else if (board.entries().isEmpty()) {
            rows.add(UiKit.textRow(Component.translatable("smartphone.parkour.board.empty"), UiKit.TEXT_3));
        } else {
            for (S2C.ParkourBoardEntry e : board.entries()) {
                int color = e.rank() == 1 ? 0xFFFFC233 : e.rank() == 2 ? 0xFFD8D8DE : e.rank() == 3 ? 0xFFD08A4E : UiKit.TEXT_2;
                String medal = "#" + e.rank();
                Component title = Component.literal(medal + "  " + e.name() + (e.me() ? " ★" : ""));
                Component sub = e.wins() > 0 ? Component.translatable("smartphone.parkour.app.wins", e.wins()) : null;
                rows.add(new UiKit.ListRow(null, title, sub, Component.literal(ParkourApp.time(e.ms())), color, null));
            }
        }
        list.rows(rows);
    }

    private void challenge() {
        String target = text("rival");
        if (target.isEmpty()) return;
        ClientPlayNetworking.send(new C2S.ParkourChallenge(course.name(), target));
        fields.put("rival", "");
        phone.refresh();
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.PARKOUR) phone.refresh();
    }
}
