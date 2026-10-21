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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Bank: overview + transfers, payment requests ("Tikkie") and standing orders. */
public class BankApp extends PhoneApp {
    private int tab;
    private boolean requested;

    public BankApp() {}

    public BankApp(int tab) {
        this.tab = tab;
    }

    @Override
    public String id() {
        return "bank";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.app.bank"), null);
        if (!requested) {
            requested = true;
            ClientPhoneState.statusKey = ""; // only show results of actions taken now
            ClientPlayNetworking.send(C2S.BankRequest.INSTANCE);
        }
        long incoming = ClientPhoneState.requests.stream().filter(S2C.RequestEntry::incoming).count();
        col.addChild(new UiKit.Segmented(w, List.of(
                Component.translatable("smartphone.bank.tab.overview"),
                Component.translatable("smartphone.bank.tab.requests").append(incoming > 0 ? " (" + incoming + ")" : ""),
                Component.translatable("smartphone.bank.tab.orders")), tab, i -> {
            tab = i;
            ClientPhoneState.statusKey = "";
            phone.refresh();
        }));
        switch (tab) {
            case 1 -> requestsTab(col);
            case 2 -> ordersTab(col);
            default -> overviewTab(col);
        }
    }

    private void status(LinearLayout col) {
        status(col, ClientPhoneState.statusKey, ClientPhoneState.statusArg, ClientPhoneState.statusOk, ClientPhoneState.statusTime);
    }

    private Component feeHint(long amount) {
        long fee = ClientPhoneState.serverInfo.feeFor(amount);
        return fee > 0 ? Component.translatable("smartphone.bank.fee_hint", fee) : Component.empty();
    }

    // ------------------------------------------------------------------ overview

    private void overviewTab(LinearLayout col) {
        // Balance card with a gradient.
        col.addChild(new UiKit.Canvas(w, 40, (g, x, y, cw, ch, hov, mx, my) -> {
            g.fillGradient(x, y, x + cw, y + ch, 0xFF1F6FEB, 0xFF5E2CA5);
            UiKit.cornerMask(g, x, y, cw, ch, 9, UiKit.BG);
            UiKit.text(g, Component.translatable("smartphone.bank.balance"), x + 8, y + 6, 0xCCFFFFFF);
            g.item(new ItemStack(Items.DIAMOND), x + 6, y + 18);
            UiKit.bigText(g, Component.literal(String.format("%,d", ClientPhoneState.balance)), x + 26, y + 19, 1.6f, UiKit.WHITE);
        }));
        LinearLayout r1 = LinearLayout.horizontal().spacing(4);
        r1.addChild(playerField("to", (w - 4) * 3 / 5, Component.translatable("smartphone.bank.target"), this::transfer));
        r1.addChild(numberField("amount", w - 4 - (w - 4) * 3 / 5, Component.translatable("smartphone.bank.amount"), 9, this::transfer));
        col.addChild(r1);
        LinearLayout r2 = LinearLayout.horizontal().spacing(4);
        r2.addChild(field("memo", w - 4 - 46, Component.translatable("smartphone.bank.memo"), 64, this::transfer));
        r2.addChild(new UiKit.Button(46, 18, Component.translatable("smartphone.bank.pay"), UiKit.Style.FILLED, UiKit.BLUE, this::transfer));
        col.addChild(r2);
        if (ClientPhoneState.serverInfo.transferFeePercent() > 0) {
            col.addChild(new UiKit.LiveLabel(() -> feeHint(number("amount")), UiKit.TEXT_2, w));
        }
        status(col);

        UiKit.ScrollList list = list("history", 2, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        rows.add(UiKit.headerRow(Component.translatable("smartphone.bank.history")));
        if (ClientPhoneState.history.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.bank.no_history"), UiKit.TEXT_2));
        SimpleDateFormat format = new SimpleDateFormat("dd-MM HH:mm");
        for (S2C.TxEntry tx : ClientPhoneState.history) {
            String sign = tx.amount() >= 0 ? "+" : "−";
            Component sub = Component.literal(format.format(new Date(tx.timestamp())) + (tx.memo().isEmpty() ? "" : " · " + tx.memo())
                    + (tx.fee() > 0 ? " · " : "")).append(tx.fee() > 0 ? Component.translatable("smartphone.bank.fee_paid", tx.fee()) : Component.empty());
            rows.add(new UiKit.ListRow(txIcon(tx), describe(tx), sub, Component.literal(sign + Math.abs(tx.amount())),
                    tx.amount() >= 0 ? UiKit.GREEN : UiKit.TEXT, null));
        }
        list.rows(rows);
    }

    private static UiKit.ListRow.Leading txIcon(S2C.TxEntry tx) {
        String glyph = switch (tx.type()) {
            case "DEPOSIT", "WITHDRAW", "REFUND" -> "▣";
            case "INTEREST" -> "%";
            case "MARKET" -> "⌂";
            case "STANDING" -> "↻";
            case "ADMIN" -> "★";
            default -> tx.counterparty().isEmpty() ? "?" : tx.counterparty().substring(0, 1).toUpperCase();
        };
        int color = tx.amount() >= 0 ? UiKit.GREEN : UiKit.BLUE;
        return (g, x, y, size) -> {
            UiKit.circle(g, x + size / 2, y + size / 2, size / 2, UiKit.withAlpha(color, 0x44));
            UiKit.textCentered(g, Component.literal(glyph), x + size / 2, y + (size - 8) / 2, color);
        };
    }

    private static Component describe(S2C.TxEntry tx) {
        return switch (tx.type()) {
            case "DEPOSIT" -> Component.translatable("smartphone.bank.tx.deposit");
            case "WITHDRAW" -> Component.translatable("smartphone.bank.tx.withdraw");
            case "REFUND" -> Component.translatable("smartphone.bank.tx.refund");
            case "INTEREST" -> Component.translatable("smartphone.bank.tx.interest");
            case "ADMIN" -> Component.translatable("smartphone.bank.tx.admin");
            case "MARKET" -> Component.translatable(tx.amount() >= 0 ? "smartphone.bank.tx.market_sold" : "smartphone.bank.tx.market_bought");
            case "REQUEST" -> Component.translatable(tx.amount() >= 0 ? "smartphone.bank.tx.request_from" : "smartphone.bank.tx.request_to", tx.counterparty());
            case "STANDING" -> Component.translatable(tx.amount() >= 0 ? "smartphone.bank.tx.order_from" : "smartphone.bank.tx.order_to", tx.counterparty());
            default -> Component.translatable(tx.amount() >= 0 ? "smartphone.bank.tx.from" : "smartphone.bank.tx.to", tx.counterparty());
        };
    }

    private void transfer() {
        String to = text("to");
        long amount = number("amount");
        if (to.isEmpty() || amount <= 0) {
            localError("smartphone.bank.error.form");
            return;
        }
        ClientPlayNetworking.send(new C2S.BankTransfer(to, amount, text("memo")));
        fields.put("amount", "");
        fields.put("memo", "");
    }

    private void localError(String key) {
        ClientPhoneState.statusKey = key;
        ClientPhoneState.statusArg = "";
        ClientPhoneState.statusOk = false;
        ClientPhoneState.statusTime = System.currentTimeMillis();
        phone.refresh();
    }

    // ------------------------------------------------------------------ Tikkie

    private void requestsTab(LinearLayout col) {
        col.addChild(new UiKit.Label(Component.translatable("smartphone.bank.request_intro"), UiKit.TEXT_2, w));
        LinearLayout r1 = LinearLayout.horizontal().spacing(4);
        r1.addChild(playerField("req_from", (w - 4) * 3 / 5, Component.translatable("smartphone.bank.target"), this::request));
        r1.addChild(numberField("req_amount", w - 4 - (w - 4) * 3 / 5, Component.translatable("smartphone.bank.amount"), 9, this::request));
        col.addChild(r1);
        LinearLayout r2 = LinearLayout.horizontal().spacing(4);
        r2.addChild(field("req_memo", w - 4 - 46, Component.translatable("smartphone.bank.memo_request"), 64, this::request));
        r2.addChild(new UiKit.Button(46, 18, Component.translatable("smartphone.bank.ask"), UiKit.Style.FILLED, UiKit.BLUE, this::request));
        col.addChild(r2);
        status(col);

        UiKit.ScrollList list = list("requests", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        List<S2C.RequestEntry> in = ClientPhoneState.requests.stream().filter(S2C.RequestEntry::incoming).toList();
        List<S2C.RequestEntry> out = ClientPhoneState.requests.stream().filter(r -> !r.incoming()).toList();
        rows.add(UiKit.headerRow(Component.translatable("smartphone.bank.requests_in")));
        if (in.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.bank.none"), UiKit.TEXT_3));
        for (S2C.RequestEntry r : in) {
            rows.add(new UiKit.ListRow(UiKit.avatarOf(r.otherName(), false),
                    Component.translatable("smartphone.bank.request_row", r.otherName(), r.amount()),
                    Component.literal(r.memo().isEmpty() ? "—" : r.memo()), null, 0, null)
                    .button(Component.translatable("smartphone.bank.pay"), UiKit.GREEN,
                            () -> ClientPlayNetworking.send(new C2S.PayRequestRespond(r.id(), true)))
                    .button(Component.literal("✕"), UiKit.RED,
                            () -> ClientPlayNetworking.send(new C2S.PayRequestRespond(r.id(), false))));
        }
        rows.add(UiKit.headerRow(Component.translatable("smartphone.bank.requests_out")));
        if (out.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.bank.none"), UiKit.TEXT_3));
        for (S2C.RequestEntry r : out) {
            rows.add(new UiKit.ListRow(UiKit.avatarOf(r.otherName(), false),
                    Component.translatable("smartphone.bank.request_row_out", r.otherName(), r.amount()),
                    Component.literal(r.memo().isEmpty() ? "—" : r.memo()), null, 0, null)
                    .button(Component.translatable("smartphone.bank.cancel"), UiKit.TEXT_2,
                            () -> ClientPlayNetworking.send(new C2S.PayRequestRespond(r.id(), false))));
        }
        list.rows(rows);
    }

    private void request() {
        String from = text("req_from");
        long amount = number("req_amount");
        if (from.isEmpty() || amount <= 0) {
            localError("smartphone.bank.error.form");
            return;
        }
        ClientPlayNetworking.send(new C2S.PayRequestCreate(from, amount, text("req_memo")));
        fields.put("req_amount", "");
        fields.put("req_memo", "");
    }

    // ------------------------------------------------------------------ standing orders

    private void ordersTab(LinearLayout col) {
        col.addChild(new UiKit.Label(Component.translatable("smartphone.bank.order_intro"), UiKit.TEXT_2, w));
        LinearLayout r1 = LinearLayout.horizontal().spacing(4);
        r1.addChild(playerField("ord_to", (w - 4) * 3 / 5, Component.translatable("smartphone.bank.target"), this::order));
        r1.addChild(numberField("ord_amount", w - 4 - (w - 4) * 3 / 5, Component.translatable("smartphone.bank.amount"), 9, this::order));
        col.addChild(r1);
        LinearLayout r2 = LinearLayout.horizontal().spacing(4);
        int daysW = 46;
        r2.addChild(numberField("ord_days", daysW, Component.translatable("smartphone.bank.days"), 2, this::order));
        r2.addChild(field("ord_memo", w - 8 - daysW - 46, Component.translatable("smartphone.bank.memo_order"), 64, this::order));
        r2.addChild(new UiKit.Button(46, 18, Component.translatable("smartphone.bank.start"), UiKit.Style.FILLED, UiKit.BLUE, this::order));
        col.addChild(r2);
        status(col);

        UiKit.ScrollList list = list("orders", 3, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        if (ClientPhoneState.orders.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.bank.no_orders"), UiKit.TEXT_3));
        for (S2C.OrderEntry o : ClientPhoneState.orders) {
            Component title = Component.translatable(o.incoming() ? "smartphone.bank.order_in" : "smartphone.bank.order_out",
                    o.otherName(), o.amount());
            Component sub = Component.translatable("smartphone.bank.order_schedule", o.intervalDays(), o.daysUntilNext())
                    .append(o.memo().isEmpty() ? "" : " · " + o.memo());
            rows.add(new UiKit.ListRow(UiKit.avatarOf(o.otherName(), false), title, sub, null, 0, null)
                    .button(Component.translatable("smartphone.bank.stop"), UiKit.RED,
                            () -> ClientPlayNetworking.send(new C2S.StandingOrderCancel(o.id()))));
        }
        list.rows(rows);
    }

    private void order() {
        String to = text("ord_to");
        long amount = number("ord_amount");
        long days = number("ord_days");
        if (to.isEmpty() || amount <= 0) {
            localError("smartphone.bank.error.form");
            return;
        }
        if (days < 1 || days > 30) {
            localError("smartphone.bank.error.interval");
            return;
        }
        ClientPlayNetworking.send(new C2S.StandingOrderCreate(to, amount, (int) days, text("ord_memo")));
        fields.put("ord_amount", "");
        fields.put("ord_memo", "");
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.BANK) phone.refresh();
    }
}
