package nl.smartphonemod.server;

import nl.smartphonemod.SmartphoneMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Things that happen once per Minecraft day: standing orders and interest.
 * The last processed day is stored in the database, so nothing runs twice after a restart.
 */
public final class EconomyService {
    private static final int MAX_CATCH_UP_DAYS = 7;
    private static long lastDay = Long.MIN_VALUE;
    private static boolean busy;

    private EconomyService() {}

    public static void start() {
        long today = PhoneServer.currentDay();
        PhoneServer.thenOnServer(PhoneServer.db().submit(c -> {
            String stored = PhoneDatabase.meta(c, "last_day", null);
            if (stored == null) {
                PhoneDatabase.setMeta(c, "last_day", Long.toString(today));
                return today;
            }
            return Long.parseLong(stored);
        }), day -> lastDay = day);
    }

    static void clear() {
        lastDay = Long.MIN_VALUE;
        busy = false;
    }

    public static void tick(MinecraftServer server) {
        if (!PhoneServer.isRunning() || lastDay == Long.MIN_VALUE || busy) return;
        if (server.getTickCount() % 20 != 0) return;
        long today = PhoneServer.currentDay();
        if (today <= lastDay) return;
        busy = true;
        long from = lastDay;
        PhoneServer.thenOnServer(PhoneServer.db().submit(c -> processDays(c, from, today)), events -> {
            lastDay = today;
            busy = false;
            for (Event e : events) e.announce();
        });
    }

    /** One thing worth telling a player about after the day's processing. */
    record Event(UUID player, String key, Object... args) {
        void announce() {
            PhoneServer.notify(player, "bank", Component.translatable(key + "_title"), Component.translatable(key, args));
        }
    }

    record OrderRow(long id, UUID payer, UUID payee, long amount, int interval, long nextDay, String memo) {}

    /** Runs on the DB thread. Returns notifications to send on the server thread. */
    static List<Event> processDays(Connection c, long fromDay, long today) throws SQLException {
        List<Event> events = new ArrayList<>();
        var cfg = PhoneServer.config();

        // Standing orders that fell due.
        List<OrderRow> due = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, payer, payee, amount, interval_days, next_day, memo FROM standing_orders WHERE active = 1 AND next_day <= ?")) {
            ps.setLong(1, today);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    due.add(new OrderRow(rs.getLong(1), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
                            rs.getLong(4), rs.getInt(5), rs.getLong(6), rs.getString(7)));
                }
            }
        }
        for (OrderRow o : due) {
            long next = o.nextDay();
            int runs = 0;
            while (next <= today && runs < MAX_CATCH_UP_DAYS) {
                long fee = cfg.transferFee(o.amount());
                PhoneDatabase.MoveStatus status = PhoneDatabase.inTransaction(c, tx ->
                        PhoneDatabase.move(tx, "STANDING", o.payer(), o.payee(), o.amount(), fee, o.memo()));
                String payeeName = PhoneDatabase.nameSync(c, o.payee());
                String payerName = PhoneDatabase.nameSync(c, o.payer());
                if (status == PhoneDatabase.MoveStatus.OK) {
                    events.add(new Event(o.payer(), "smartphone.notify.order_paid", o.amount(), payeeName));
                    events.add(new Event(o.payee(), "smartphone.notify.order_received", o.amount(), payerName));
                } else {
                    events.add(new Event(o.payer(), "smartphone.notify.order_failed", o.amount(), payeeName));
                    events.add(new Event(o.payee(), "smartphone.notify.order_missed", o.amount(), payerName));
                }
                next += o.interval();
                runs++;
            }
            if (next <= today) next = today + o.interval(); // skip what can't be caught up
            try (PreparedStatement ps = c.prepareStatement("UPDATE standing_orders SET next_day = ? WHERE id = ?")) {
                ps.setLong(1, next);
                ps.setLong(2, o.id());
                ps.executeUpdate();
            }
        }

        // Interest, once per elapsed day (capped), over at most interestMaxBalance.
        if (cfg.dailyInterestPercent > 0) {
            long days = Math.min(MAX_CATCH_UP_DAYS, today - fromDay);
            List<UUID> accounts = new ArrayList<>();
            List<Long> amounts = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, balance FROM accounts WHERE balance > 0")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long base = Math.min(rs.getLong(2), cfg.interestMaxBalance);
                        long interest = (long) Math.floor(base * cfg.dailyInterestPercent / 100.0) * days;
                        if (interest > 0) {
                            UUID u = PhoneDatabase.uuid(rs.getString(1)); if (u == null) continue; accounts.add(u);
                            amounts.add(interest);
                        }
                    }
                }
            }
            PhoneDatabase.inTransaction(c, tx -> {
                for (int i = 0; i < accounts.size(); i++) {
                    PhoneDatabase.credit(tx, accounts.get(i), amounts.get(i));
                    PhoneDatabase.log(tx, "INTEREST", null, accounts.get(i), amounts.get(i), 0, "");
                }
                return null;
            });
            for (int i = 0; i < accounts.size(); i++) {
                events.add(new Event(accounts.get(i), "smartphone.notify.interest", amounts.get(i)));
            }
        }

        PhoneDatabase.setMeta(c, "last_day", Long.toString(today));
        if (!due.isEmpty() || cfg.dailyInterestPercent > 0) {
            SmartphoneMod.LOGGER.info("Smartphone: processed day {} ({} standing orders)", today, due.size());
        }
        return events;
    }
}
