package nl.smartphonemod.server;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.network.S2C;
import nl.smartphonemod.registry.ModItems;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Marktplaats. Listed items are held by the server (escrow, as JSON in the database) until they
 * are bought or taken back. Items handed out always go through {@code pending_items}, so a player
 * who disconnects at the wrong moment simply receives them at their next login.
 */
public final class MarketService {
    private static final int PAGE = 100;

    private MarketService() {}

    // ---------------------------------------------------------------- item <-> JSON (server thread)

    static String encode(MinecraftServer server, ItemStack stack) {
        var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().toString();
    }

    static ItemStack decode(MinecraftServer server, String json) {
        try {
            var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            return ItemStack.CODEC.parse(ops, JsonParser.parseString(json)).result().orElse(ItemStack.EMPTY);
        } catch (RuntimeException e) {
            SmartphoneMod.LOGGER.warn("Could not read a stored market item", e);
            return ItemStack.EMPTY;
        }
    }

    /** Display name, also used for search (SQLite LIKE is case-insensitive). */
    private static String searchName(ItemStack stack) {
        return stack.getCount() + "x " + stack.getHoverName().getString();
    }

    // ---------------------------------------------------------------- browse

    private record Row(long id, String seller, String item, long price, boolean mine) {}

    public static void sendMarket(ServerPlayer player, String query, String statusKey, String statusArg, boolean ok) {
        UUID me = player.getUUID();
        String q = MessageService.sanitize(query, 32).toLowerCase(Locale.ROOT);
        PhoneServer.then(PhoneServer.db().submit(c -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT l.id, a.name, l.item, l.price, l.seller FROM listings l
                    LEFT JOIN accounts a ON a.uuid = l.seller
                    WHERE l.status = 'ACTIVE' AND (l.seller = ?1 OR l.item_name LIKE ?2 ESCAPE '\\')
                    ORDER BY (l.seller = ?1) DESC, l.id DESC LIMIT ?3""")) {
                ps.setString(1, me.toString());
                ps.setString(2, "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                ps.setInt(3, PAGE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getLong(1), rs.getString(2) == null ? "?" : rs.getString(2), rs.getString(3),
                                rs.getLong(4), me.toString().equals(rs.getString(5))));
                    }
                }
            }
            return rows;
        }), player, (p, rows) -> {
            MinecraftServer server = p.level().getServer();
            List<S2C.ListingEntry> entries = new ArrayList<>();
            for (Row r : rows) {
                ItemStack stack = decode(server, r.item());
                if (!stack.isEmpty()) entries.add(new S2C.ListingEntry(r.id(), r.seller(), stack, r.price(), r.mine()));
            }
            ServerPlayNetworking.send(p, new S2C.MarketData(entries, statusKey, statusArg, ok));
        });
    }

    // ---------------------------------------------------------------- sell

    public static void sell(ServerPlayer player, int slot, long price) {
        var cfg = PhoneServer.config();
        if (slot < 0 || slot >= 36) return;
        if (price <= 0 || price > cfg.maxListingPrice) {
            sendMarket(player, "", "smartphone.market.error.price", Long.toString(cfg.maxListingPrice), false);
            return;
        }
        if (!Cooldowns.tryUse(player.getUUID(), "market", 500)) return;
        var inventory = player.getInventory();
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) return;
        boolean heldPhone = stack.is(ModItems.SMARTPHONE) && PhoneServer.phoneCount(player) <= 1; // you need one to keep using the app
        if (heldPhone) {
            sendMarket(player, "", "smartphone.market.error.held_phone", "", false);
            return;
        }
        MinecraftServer server = player.level().getServer();
        ItemStack taken = inventory.removeItemNoUpdate(slot); // the item leaves the player first...
        inventory.setChanged();
        String json;
        try {
            json = encode(server, taken);
        } catch (RuntimeException e) {
            BankService.giveOrDrop(player, taken);
            sendMarket(player, "", "smartphone.market.error.item", "", false);
            return;
        }
        String name = searchName(taken);
        String display = taken.getCount() + "x " + taken.getHoverName().getString();
        UUID me = player.getUUID();
        int max = cfg.maxListingsPerPlayer;
        PhoneServer.db().transaction(tx -> {
            try (PreparedStatement ps = tx.prepareStatement("SELECT COUNT(*) FROM listings WHERE seller = ? AND status = 'ACTIVE'")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= max) return false;
                }
            }
            try (PreparedStatement ps = tx.prepareStatement(
                    "INSERT INTO listings(seller, item, item_name, price, ts) VALUES (?,?,?,?,?)")) {
                ps.setString(1, me.toString());
                ps.setString(2, json);
                ps.setString(3, name);
                ps.setLong(4, price);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return true;
        }).whenComplete((ok, error) -> server.execute(() -> {
            // ...and comes back if it could not be listed.
            ServerPlayer p = server.getPlayerList().getPlayer(me);
            if (error != null || !ok) {
                if (error != null) SmartphoneMod.LOGGER.error("Listing failed", error);
                if (p != null) {
                    BankService.giveOrDrop(p, taken);
                    sendMarket(p, "", error != null ? "smartphone.error.offline" : "smartphone.market.error.too_many",
                            Integer.toString(max), false);
                } else {
                    storePending(me, json, "listing-failed");
                }
                return;
            }
            if (p != null) sendMarket(p, "", "smartphone.market.listed", display + "|" + price, true);
        }));
    }

    // ---------------------------------------------------------------- buy / cancel

    private record Outcome(String status, UUID seller, String itemJson, long price, long fee) {}

    public static void buy(ServerPlayer player, long id) {
        if (!Cooldowns.tryUse(player.getUUID(), "market", 500)) return;
        UUID me = player.getUUID();
        var cfg = PhoneServer.config();
        PhoneServer.thenOnServer(PhoneServer.db().transaction(tx -> {
            String item;
            UUID seller;
            long price;
            try (PreparedStatement ps = tx.prepareStatement("SELECT seller, item, price FROM listings WHERE id = ? AND status = 'ACTIVE'")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return new Outcome("gone", null, "", 0, 0);
                    seller = UUID.fromString(rs.getString(1));
                    item = rs.getString(2);
                    price = rs.getLong(3);
                }
            }
            if (seller.equals(me)) return new Outcome("own", seller, "", price, 0);
            long fee = Math.min(price, cfg.marketFee(price));
            // Buyer pays the full price; the seller receives price - fee.
            if (PhoneDatabase.move(tx, "MARKET", me, seller, price - fee, fee, itemDisplay(tx, id)) != PhoneDatabase.MoveStatus.OK) {
                return new Outcome("insufficient", seller, item, price, fee);
            }
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE listings SET status = 'SOLD', buyer = ? WHERE id = ? AND status = 'ACTIVE'")) {
                ps.setString(1, me.toString());
                ps.setLong(2, id);
                if (ps.executeUpdate() != 1) throw new SQLException("Listing sold concurrently");
            }
            insertPending(tx, me, item, "bought");
            return new Outcome("ok", seller, item, price, fee);
        }), out -> {
            ServerPlayer p = PhoneServer.online(me);
            String itemName = "";
            if (out.itemJson() != null && !out.itemJson().isEmpty()) {
                ItemStack stack = decode(PhoneServer.server(), out.itemJson());
                itemName = stack.getCount() + "x " + stack.getHoverName().getString();
            }
            switch (out.status()) {
                case "gone" -> { if (p != null) sendMarket(p, "", "smartphone.market.error.gone", "", false); }
                case "own" -> { if (p != null) sendMarket(p, "", "smartphone.market.error.own", "", false); }
                case "insufficient" -> { if (p != null) sendMarket(p, "", "smartphone.bank.error.insufficient", "", false); }
                default -> {
                    if (p != null) {
                        deliverPending(p);
                        sendMarket(p, "", "smartphone.market.bought", itemName + "|" + out.price(), true);
                    }
                    PhoneServer.notify(out.seller(), "market", Component.translatable("smartphone.notify.sold_title"),
                            Component.translatable("smartphone.notify.sold_body", itemName, out.price() - out.fee()));
                }
            }
        });
    }

    public static void cancel(ServerPlayer player, long id) {
        UUID me = player.getUUID();
        PhoneServer.thenOnServer(PhoneServer.db().transaction(tx -> {
            String item;
            try (PreparedStatement ps = tx.prepareStatement("SELECT item FROM listings WHERE id = ? AND seller = ? AND status = 'ACTIVE'")) {
                ps.setLong(1, id);
                ps.setString(2, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    item = rs.getString(1);
                }
            }
            try (PreparedStatement ps = tx.prepareStatement("UPDATE listings SET status = 'CANCELLED' WHERE id = ? AND status = 'ACTIVE'")) {
                ps.setLong(1, id);
                if (ps.executeUpdate() != 1) return false;
            }
            insertPending(tx, me, item, "cancelled");
            return true;
        }), ok -> {
            ServerPlayer p = PhoneServer.online(me);
            if (p == null) return;
            if (ok) deliverPending(p);
            sendMarket(p, "", ok ? "smartphone.market.cancelled" : "smartphone.market.error.gone", "", ok);
        });
    }

    private static String itemDisplay(Connection tx, long id) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT item_name FROM listings WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    // ---------------------------------------------------------------- pending deliveries

    private record Pending(long id, String item) {}

    private static void insertPending(Connection tx, UUID owner, String item, String reason) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO pending_items(owner, item, reason) VALUES (?,?,?)")) {
            ps.setString(1, owner.toString());
            ps.setString(2, item);
            ps.setString(3, reason);
            ps.executeUpdate();
        }
    }

    private static void storePending(UUID owner, String item, String reason) {
        PhoneServer.db().submit(c -> {
            insertPending(c, owner, item, reason);
            return null;
        });
    }

    /** Gives the player everything waiting for them. Rows are claimed atomically, so nothing is handed out twice. */
    static void deliverPending(ServerPlayer player) {
        if (!PhoneServer.isRunning()) return;
        UUID me = player.getUUID();
        MinecraftServer server = player.level().getServer();
        PhoneServer.db().transaction(tx -> {
            List<Pending> claimed = new ArrayList<>();
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM pending_items WHERE owner = ? RETURNING id, item")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) claimed.add(new Pending(rs.getLong(1), rs.getString(2)));
                }
            }
            return claimed;
        }).whenComplete((claimed, error) -> server.execute(() -> {
            if (error != null || claimed == null || claimed.isEmpty()) {
                if (error != null) SmartphoneMod.LOGGER.error("Could not deliver pending items", error);
                return;
            }
            ServerPlayer p = server.getPlayerList().getPlayer(me);
            for (Pending item : claimed) {
                if (p == null) { // left in the meantime: put it back for next time
                    storePending(me, item.item(), "redelivery");
                    continue;
                }
                ItemStack stack = decode(server, item.item());
                if (!stack.isEmpty()) BankService.giveOrDrop(p, stack);
            }
        }));
    }
}
