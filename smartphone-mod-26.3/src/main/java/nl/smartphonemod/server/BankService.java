package nl.smartphonemod.server;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** All money logic: balance, transfers, payment requests ("Tikkie"), standing orders, ATM. */
public final class BankService {
    private static final int HISTORY_LIMIT = 30;
    private static final int MAX_OPEN_REQUESTS = 20;
    private static final int MAX_ORDERS = 10;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private BankService() {}

    /** Puts items in the player's inventory, dropping whatever doesn't fit at their feet. */
    static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        player.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    static String memo(String raw) {
        return MessageService.sanitize(raw, 64);
    }

    // ---------------------------------------------------------------- read

    private record Snapshot(long balance, List<S2C.TxEntry> history, List<S2C.RequestEntry> requests, List<S2C.OrderEntry> orders) {}

    public static void sendBankData(ServerPlayer player, String statusKey, String statusArg, boolean ok) {
        if (!PhoneServer.isRunning()) return;
        UUID me = player.getUUID();
        long today = PhoneServer.currentDay();
        PhoneServer.then(PhoneServer.db().submit(c -> new Snapshot(PhoneDatabase.balanceSync(c, me),
                        history(c, me), requests(c, me), orders(c, me, today))), player,
                (p, s) -> ServerPlayNetworking.send(p, new S2C.BankData(s.balance, s.history, s.requests, s.orders, statusKey, statusArg, ok)));
    }

    private static List<S2C.TxEntry> history(Connection c, UUID me) throws SQLException {
        List<S2C.TxEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT t.type, t.from_uuid, t.amount, t.fee, t.memo, t.ts, fa.name, ta.name
                FROM transactions t
                LEFT JOIN accounts fa ON fa.uuid = t.from_uuid
                LEFT JOIN accounts ta ON ta.uuid = t.to_uuid
                WHERE t.from_uuid = ?1 OR t.to_uuid = ?1
                ORDER BY t.id DESC LIMIT ?2""")) {
            ps.setString(1, me.toString());
            ps.setInt(2, HISTORY_LIMIT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean outgoing = me.toString().equals(rs.getString(2));
                    long amount = rs.getLong(3), fee = rs.getLong(4);
                    String other = outgoing ? rs.getString(8) : rs.getString(7);
                    out.add(new S2C.TxEntry(rs.getString(1), other == null ? "" : other,
                            outgoing ? -(amount + fee) : amount, outgoing ? fee : 0, rs.getString(5), rs.getLong(6)));
                }
            }
        }
        return out;
    }

    private static List<S2C.RequestEntry> requests(Connection c, UUID me) throws SQLException {
        List<S2C.RequestEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT r.id, r.requester, r.amount, r.memo, r.ts, ra.name, pa.name
                FROM pay_requests r
                LEFT JOIN accounts ra ON ra.uuid = r.requester
                LEFT JOIN accounts pa ON pa.uuid = r.payer
                WHERE r.status = 'OPEN' AND (r.payer = ?1 OR r.requester = ?1)
                ORDER BY r.id DESC LIMIT 50""")) {
            ps.setString(1, me.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean incoming = !me.toString().equals(rs.getString(2));
                    String other = incoming ? rs.getString(6) : rs.getString(7);
                    out.add(new S2C.RequestEntry(rs.getLong(1), other == null ? "?" : other, rs.getLong(3),
                            rs.getString(4), incoming, rs.getLong(5)));
                }
            }
        }
        return out;
    }

    private static List<S2C.OrderEntry> orders(Connection c, UUID me, long today) throws SQLException {
        List<S2C.OrderEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT o.id, o.payer, o.amount, o.interval_days, o.next_day, o.memo, pa.name, pe.name
                FROM standing_orders o
                LEFT JOIN accounts pa ON pa.uuid = o.payer
                LEFT JOIN accounts pe ON pe.uuid = o.payee
                WHERE o.active = 1 AND (o.payer = ?1 OR o.payee = ?1)
                ORDER BY o.id DESC""")) {
            ps.setString(1, me.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean incoming = !me.toString().equals(rs.getString(2));
                    String other = incoming ? rs.getString(7) : rs.getString(8);
                    out.add(new S2C.OrderEntry(rs.getLong(1), other == null ? "?" : other, rs.getLong(3), rs.getInt(4),
                            Math.max(0, rs.getLong(5) - today), rs.getString(6), incoming));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- validation

    /** Common checks for name + amount. Returns a status key, or null when everything is fine. */
    private static String validate(ServerPlayer player, String target, long amount, String cooldownKey) {
        var cfg = PhoneServer.config();
        if (target == null || !NAME.matcher(target).matches()) return "smartphone.bank.error.unknown_player";
        if (amount <= 0 || amount > cfg.maxTransfer) return "smartphone.bank.error.amount";
        if (!Cooldowns.tryUse(player.getUUID(), cooldownKey, cfg.transferCooldownMs)) return "smartphone.error.slow_down";
        return null;
    }

    private static String argFor(String key, String target) {
        return switch (key) {
            case "smartphone.bank.error.unknown_player" -> String.valueOf(target);
            case "smartphone.bank.error.amount" -> String.valueOf(PhoneServer.config().maxTransfer);
            default -> "";
        };
    }

    // ---------------------------------------------------------------- transfer

    private enum Result { OK, UNKNOWN, SELF, INSUFFICIENT, LIMIT, NOT_FOUND }

    private record TransferOutcome(Result result, UUID target, String targetName, long fee) {}

    public static void transfer(ServerPlayer player, String target, long amount, String rawMemo) {
        String error = validate(player, target, amount, "transfer");
        if (error != null) {
            sendBankData(player, error, argFor(error, target), false);
            return;
        }
        String memo = memo(rawMemo);
        long fee = PhoneServer.config().transferFee(amount);
        UUID me = player.getUUID();
        PhoneServer.then(PhoneServer.db().transaction(tx -> {
            UUID to = PhoneDatabase.uuidByName(tx, target);
            if (to == null) return new TransferOutcome(Result.UNKNOWN, null, target, fee);
            if (to.equals(me)) return new TransferOutcome(Result.SELF, to, target, fee);
            String name = PhoneDatabase.nameSync(tx, to);
            if (PhoneDatabase.move(tx, "TRANSFER", me, to, amount, fee, memo) != PhoneDatabase.MoveStatus.OK) {
                return new TransferOutcome(Result.INSUFFICIENT, to, name, fee);
            }
            return new TransferOutcome(Result.OK, to, name, fee);
        }), player, (p, out) -> {
            switch (out.result()) {
                case UNKNOWN -> sendBankData(p, "smartphone.bank.error.unknown_player", target, false);
                case SELF -> sendBankData(p, "smartphone.bank.error.self", "", false);
                case INSUFFICIENT -> sendBankData(p, "smartphone.bank.error.insufficient", "", false);
                default -> {
                    sendBankData(p, "smartphone.bank.sent", amount + "|" + out.targetName(), true);
                    paid(out.target(), p, amount, memo);
                }
            }
        });
    }

    /** Tells the receiver about incoming money. */
    private static void paid(UUID receiver, ServerPlayer payer, long amount, String memo) {
        PhoneServer.notify(receiver, "bank",
                Component.translatable("smartphone.notify.received_title"),
                memo.isEmpty() ? Component.translatable("smartphone.bank.received", amount, payer.getName())
                        : Component.translatable("smartphone.bank.received_memo", amount, payer.getName(), memo));
        ServerPlayer r = PhoneServer.online(receiver);
        if (r != null) {
            r.level().playSound(null, r.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6f, 1.4f);
            if (PhoneServer.hasPhone(r)) sendBankData(r, "", "", true);
        }
    }

    // ---------------------------------------------------------------- payment requests ("Tikkie")

    public static void createRequest(ServerPlayer player, String target, long amount, String rawMemo) {
        String error = validate(player, target, amount, "request");
        if (error != null) {
            sendBankData(player, error, argFor(error, target), false);
            return;
        }
        String memo = memo(rawMemo);
        UUID me = player.getUUID();
        PhoneServer.then(PhoneServer.db().transaction(tx -> {
            UUID to = PhoneDatabase.uuidByName(tx, target);
            if (to == null) return new TransferOutcome(Result.UNKNOWN, null, target, 0);
            if (to.equals(me)) return new TransferOutcome(Result.SELF, to, target, 0);
            try (PreparedStatement ps = tx.prepareStatement("SELECT COUNT(*) FROM pay_requests WHERE requester = ? AND status = 'OPEN'")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= MAX_OPEN_REQUESTS) return new TransferOutcome(Result.LIMIT, to, target, 0);
                }
            }
            try (PreparedStatement ps = tx.prepareStatement(
                    "INSERT INTO pay_requests(requester, payer, amount, memo, ts) VALUES (?,?,?,?,?)")) {
                ps.setString(1, me.toString());
                ps.setString(2, to.toString());
                ps.setLong(3, amount);
                ps.setString(4, memo);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return new TransferOutcome(Result.OK, to, PhoneDatabase.nameSync(tx, to), 0);
        }), player, (p, out) -> {
            switch (out.result()) {
                case UNKNOWN -> sendBankData(p, "smartphone.bank.error.unknown_player", target, false);
                case SELF -> sendBankData(p, "smartphone.bank.error.self_request", "", false);
                case LIMIT -> sendBankData(p, "smartphone.bank.error.too_many_requests", "", false);
                default -> {
                    sendBankData(p, "smartphone.bank.request_sent", amount + "|" + out.targetName(), true);
                    PhoneServer.notify(out.target(), "bank", Component.translatable("smartphone.notify.request_title", p.getName()),
                            memo.isEmpty() ? Component.translatable("smartphone.notify.request_body", amount)
                                    : Component.translatable("smartphone.notify.request_body_memo", amount, memo));
                    refreshIfHolding(out.target());
                }
            }
        });
    }

    private record RequestRow(UUID requester, UUID payer, long amount, String memo) {}

    private record RespondOutcome(Result result, RequestRow request, boolean paid, long fee) {}

    /** Payer accepts (pays) or declines; requester may cancel (accept=false). */
    public static void respondRequest(ServerPlayer player, long id, boolean accept) {
        UUID me = player.getUUID();
        PhoneServer.then(PhoneServer.db().transaction(tx -> {
            RequestRow row = null;
            try (PreparedStatement ps = tx.prepareStatement(
                    "SELECT requester, payer, amount, memo FROM pay_requests WHERE id = ? AND status = 'OPEN'")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) row = new RequestRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                            rs.getLong(3), rs.getString(4));
                }
            }
            if (row == null || !(row.payer().equals(me) || row.requester().equals(me))) {
                return new RespondOutcome(Result.NOT_FOUND, null, false, 0);
            }
            boolean payerResponds = row.payer().equals(me);
            String newStatus;
            long fee = 0;
            if (payerResponds && accept) {
                fee = PhoneServer.config().transferFee(row.amount());
                if (PhoneDatabase.move(tx, "REQUEST", me, row.requester(), row.amount(), fee, row.memo()) != PhoneDatabase.MoveStatus.OK) {
                    return new RespondOutcome(Result.INSUFFICIENT, row, false, fee);
                }
                newStatus = "PAID";
            } else {
                newStatus = payerResponds ? "DECLINED" : "CANCELLED";
            }
            try (PreparedStatement ps = tx.prepareStatement("UPDATE pay_requests SET status = ? WHERE id = ? AND status = 'OPEN'")) {
                ps.setString(1, newStatus);
                ps.setLong(2, id);
                if (ps.executeUpdate() != 1) throw new SQLException("Request changed concurrently");
            }
            return new RespondOutcome(Result.OK, row, "PAID".equals(newStatus), fee);
        }), player, (p, out) -> {
            switch (out.result()) {
                case NOT_FOUND -> sendBankData(p, "smartphone.bank.error.request_gone", "", false);
                case INSUFFICIENT -> sendBankData(p, "smartphone.bank.error.insufficient", "", false);
                default -> {
                    RequestRow r = out.request();
                    boolean iAmPayer = r.payer().equals(me);
                    if (out.paid()) {
                        sendBankData(p, "smartphone.bank.request_paid", Long.toString(r.amount()), true);
                        paid(r.requester(), p, r.amount(), r.memo());
                    } else if (iAmPayer) {
                        sendBankData(p, "smartphone.bank.request_declined", "", true);
                        PhoneServer.notify(r.requester(), "bank", Component.translatable("smartphone.notify.request_declined_title"),
                                Component.translatable("smartphone.notify.request_declined_body", p.getName(), r.amount()));
                        refreshIfHolding(r.requester());
                    } else {
                        sendBankData(p, "smartphone.bank.request_cancelled", "", true);
                        refreshIfHolding(r.payer());
                    }
                }
            }
        });
    }

    /** On login: tell the player about payment requests waiting for them. */
    static void announceOpenRequests(ServerPlayer player) {
        PhoneServer.then(PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pay_requests WHERE payer = ? AND status = 'OPEN'")) {
                ps.setString(1, player.getUUID().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }), player, (p, count) -> {
            if (count > 0) {
                ServerPlayNetworking.send(p, new S2C.Notify("bank", Component.translatable("smartphone.notify.open_requests_title"),
                        Component.translatable("smartphone.notify.open_requests_body", count)));
            }
        });
    }

    // ---------------------------------------------------------------- standing orders

    public static void createOrder(ServerPlayer player, String target, long amount, int intervalDays, String rawMemo) {
        String error = validate(player, target, amount, "order");
        if (error == null && (intervalDays < 1 || intervalDays > 30)) error = "smartphone.bank.error.interval";
        if (error != null) {
            sendBankData(player, error, argFor(error, target), false);
            return;
        }
        String memo = memo(rawMemo);
        UUID me = player.getUUID();
        long firstDay = PhoneServer.currentDay() + 1; // first payment at the start of the next Minecraft day
        PhoneServer.then(PhoneServer.db().transaction(tx -> {
            UUID to = PhoneDatabase.uuidByName(tx, target);
            if (to == null) return new TransferOutcome(Result.UNKNOWN, null, target, 0);
            if (to.equals(me)) return new TransferOutcome(Result.SELF, to, target, 0);
            try (PreparedStatement ps = tx.prepareStatement("SELECT COUNT(*) FROM standing_orders WHERE payer = ? AND active = 1")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= MAX_ORDERS) return new TransferOutcome(Result.LIMIT, to, target, 0);
                }
            }
            try (PreparedStatement ps = tx.prepareStatement(
                    "INSERT INTO standing_orders(payer, payee, amount, interval_days, next_day, memo) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, me.toString());
                ps.setString(2, to.toString());
                ps.setLong(3, amount);
                ps.setInt(4, intervalDays);
                ps.setLong(5, firstDay);
                ps.setString(6, memo);
                ps.executeUpdate();
            }
            return new TransferOutcome(Result.OK, to, PhoneDatabase.nameSync(tx, to), 0);
        }), player, (p, out) -> {
            switch (out.result()) {
                case UNKNOWN -> sendBankData(p, "smartphone.bank.error.unknown_player", target, false);
                case SELF -> sendBankData(p, "smartphone.bank.error.self", "", false);
                case LIMIT -> sendBankData(p, "smartphone.bank.error.too_many_orders", Integer.toString(MAX_ORDERS), false);
                default -> {
                    sendBankData(p, "smartphone.bank.order_created", amount + "|" + out.targetName() + "|" + intervalDays, true);
                    PhoneServer.notify(out.target(), "bank", Component.translatable("smartphone.notify.order_title"),
                            Component.translatable("smartphone.notify.order_body", p.getName(), amount, intervalDays));
                }
            }
        });
    }

    public static void cancelOrder(ServerPlayer player, long id) {
        UUID me = player.getUUID();
        PhoneServer.then(PhoneServer.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE standing_orders SET active = 0 WHERE id = ?2 AND active = 1 AND (payer = ?1 OR payee = ?1)")) {
                ps.setString(1, me.toString());
                ps.setLong(2, id);
                return ps.executeUpdate() == 1;
            }
        }), player, (p, ok) -> sendBankData(p, ok ? "smartphone.bank.order_cancelled" : "smartphone.bank.error.request_gone", "", ok));
    }

    private static void refreshIfHolding(UUID uuid) {
        ServerPlayer other = PhoneServer.online(uuid);
        if (other != null && PhoneServer.hasPhone(other)) sendBankData(other, "", "", true);
    }

    // ---------------------------------------------------------------- ATM

    /**
     * Credits a deposit. The physical items must already be destroyed by the caller; if the database
     * write fails they are handed back, so nothing is ever lost or duplicated.
     */
    public static void deposit(ServerPlayer player, long value, ItemStack itemsForRefund) {
        if (!PhoneServer.isRunning()) {
            giveOrDrop(player, itemsForRefund);
            return;
        }
        UUID uuid = player.getUUID();
        var server = player.level().getServer();
        PhoneServer.db().transaction(tx -> {
            PhoneDatabase.credit(tx, uuid, value);
            PhoneDatabase.log(tx, "DEPOSIT", null, uuid, value, 0, "");
            return PhoneDatabase.balanceSync(tx, uuid);
        }).whenComplete((balance, error) -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (error != null) {
                SmartphoneMod.LOGGER.error("Deposit failed, returning items to {}", uuid, error);
                if (p != null) {
                    giveOrDrop(p, itemsForRefund);
                    p.sendOverlayMessage(Component.translatable("smartphone.error.offline").withStyle(ChatFormatting.RED));
                }
                return;
            }
            if (p != null) {
                p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8f, 1.2f);
                sendBankData(p, "smartphone.atm.deposited", String.valueOf(value), true);
            }
        }));
    }

    /** Debits first, then hands out the items. If the player vanished in between, the debit is refunded. */
    public static void withdraw(ServerPlayer player, ItemStack items, long cost) {
        if (!PhoneServer.isRunning() || cost <= 0 || items.isEmpty()) return;
        if (!Cooldowns.tryUse(player.getUUID(), "withdraw", 150)) return;
        UUID uuid = player.getUUID();
        var server = player.level().getServer();
        var db = PhoneServer.db();
        db.transaction(tx -> {
            if (!PhoneDatabase.debit(tx, uuid, cost)) return -1L;
            PhoneDatabase.log(tx, "WITHDRAW", uuid, null, cost, 0, "");
            return PhoneDatabase.balanceSync(tx, uuid);
        }).whenComplete((balance, error) -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (error != null) {
                SmartphoneMod.LOGGER.error("Withdrawal failed for {}", uuid, error);
                if (p != null) p.sendOverlayMessage(Component.translatable("smartphone.error.offline").withStyle(ChatFormatting.RED));
                return;
            }
            if (balance < 0) {
                if (p != null) sendBankData(p, "smartphone.bank.error.insufficient", "", false);
                return;
            }
            if (p == null || p.isRemoved()) {
                db.transaction(tx -> { // money stays digital; nothing is created out of thin air
                    PhoneDatabase.credit(tx, uuid, cost);
                    PhoneDatabase.log(tx, "REFUND", null, uuid, cost, 0, "");
                    return null;
                });
                return;
            }
            giveOrDrop(p, items.copy());
            p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.7f, 1.0f);
            sendBankData(p, "smartphone.atm.withdrew", String.valueOf(cost), true);
        }));
    }
}
