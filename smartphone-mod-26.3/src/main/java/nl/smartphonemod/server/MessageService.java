package nl.smartphonemod.server;

import nl.smartphonemod.network.Conv;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Direct messages and group chats. Works for offline recipients: messages wait in the database. */
public final class MessageService {
    private static final int THREAD_LIMIT = 60;
    private static final int MAX_GROUPS_PER_PLAYER = 20;

    private MessageService() {}

    // ---------------------------------------------------------------- contacts

    private record ContactRow(UUID uuid, String name, int unread, boolean friend) {}

    private record ContactsResult(List<ContactRow> contacts, List<S2C.GroupEntry> groups) {}

    public static void sendContacts(ServerPlayer player) {
        UUID me = player.getUUID();
        PhoneServer.then(PhoneServer.db().submit(c -> new ContactsResult(contacts(c, me), groups(c, me))), player, (p, r) -> {
            // Online status is only known on the server thread, so it is added here.
            List<S2C.ContactEntry> entries = r.contacts().stream()
                    .map(x -> new S2C.ContactEntry(x.uuid(), x.name(), PhoneServer.online(x.uuid()) != null, x.unread(), x.friend()))
                    .sorted(Comparator.comparing((S2C.ContactEntry x) -> x.unread() == 0)
                            .thenComparing(x -> !x.online())
                            .thenComparing(x -> x.name().toLowerCase(Locale.ROOT)))
                    .toList();
            ServerPlayNetworking.send(p, new S2C.Contacts(entries, r.groups()));
        });
    }

    private static List<ContactRow> contacts(Connection c, UUID me) throws SQLException {
        Map<String, Integer> unread = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sender, COUNT(*) FROM messages WHERE recipient = ? AND group_id = 0 AND is_read = 0 GROUP BY sender")) {
            ps.setString(1, me.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) unread.put(rs.getString(1), rs.getInt(2));
            }
        }
        Set<String> friends = new java.util.HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT friend FROM friends WHERE owner = ?")) {
            ps.setString(1, me.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) friends.add(rs.getString(1));
            }
        }
        List<ContactRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, name FROM accounts WHERE uuid <> ? ORDER BY last_seen DESC LIMIT 500")) {
            ps.setString(1, me.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    UUID u = PhoneDatabase.uuid(id);
                    if (u == null) continue;
                    out.add(new ContactRow(u, rs.getString(2), unread.getOrDefault(id, 0), friends.contains(id)));
                }
            }
        }
        return out;
    }

    private static List<S2C.GroupEntry> groups(Connection c, UUID me) throws SQLException {
        List<S2C.GroupEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT g.id, g.name,
                       (SELECT COUNT(*) FROM group_members x WHERE x.group_id = g.id),
                       (SELECT COUNT(*) FROM messages m WHERE m.group_id = g.id AND m.id > gm.last_read AND m.sender <> ?1),
                       (SELECT COALESCE(MAX(m.id), 0) FROM messages m WHERE m.group_id = g.id)
                FROM chat_groups g JOIN group_members gm ON gm.group_id = g.id AND gm.uuid = ?1
                ORDER BY 5 DESC""")) {
            ps.setString(1, me.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new S2C.GroupEntry(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
            }
        }
        return out;
    }

    static boolean isMember(Connection c, long group, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM group_members WHERE group_id = ? AND uuid = ?")) {
            ps.setLong(1, group);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static List<UUID> members(Connection c, long group) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM group_members WHERE group_id = ?")) {
            ps.setLong(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { UUID u = PhoneDatabase.uuid(rs.getString(1)); if (u != null) out.add(u); }
            }
        }
        return out;
    }

    private static String groupName(Connection c, long group) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM chat_groups WHERE id = ?")) {
            ps.setLong(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---------------------------------------------------------------- threads

    private record ThreadResult(String title, List<UUID> members, List<String> memberNames, List<S2C.MessageEntry> messages) {}

    public static void openThread(ServerPlayer player, Conv conv) {
        UUID me = player.getUUID();
        if (!conv.isGroup() && conv.player().equals(me)) return;
        var db = PhoneServer.db();
        PhoneServer.then(db.transaction(tx -> conv.isGroup() ? groupThread(tx, me, conv.group()) : directThread(tx, me, conv.player())),
                player, (p, t) -> {
                    if (t == null) return; // unknown player or not a member: ignore
                    boolean online = conv.isGroup()
                            ? t.members().stream().anyMatch(u -> !u.equals(me) && PhoneServer.online(u) != null)
                            : PhoneServer.online(conv.player()) != null;
                    ServerPlayNetworking.send(p, new S2C.ChatThread(conv, t.title(), online, t.memberNames(), t.messages()));
                    PhoneServer.sendUnread(p, false); // opening marked messages as read
                });
    }

    private static ThreadResult directThread(Connection tx, UUID me, UUID other) throws SQLException {
        String otherName = PhoneDatabase.nameSync(tx, other);
        if (otherName == null) return null;
        List<S2C.MessageEntry> messages = new ArrayList<>();
        String myName = PhoneDatabase.nameSync(tx, me);
        try (PreparedStatement ps = tx.prepareStatement("""
                SELECT sender, body, ts, photo_id FROM messages
                WHERE group_id = 0 AND ((sender = ?1 AND recipient = ?2) OR (sender = ?2 AND recipient = ?1))
                ORDER BY id DESC LIMIT ?3""")) {
            ps.setString(1, me.toString());
            ps.setString(2, other.toString());
            ps.setInt(3, THREAD_LIMIT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean mine = me.toString().equals(rs.getString(1));
                    messages.add(new S2C.MessageEntry(mine, mine ? myName : otherName, rs.getString(2), rs.getLong(4), rs.getLong(3)));
                }
            }
        }
        Collections.reverse(messages);
        try (PreparedStatement ps = tx.prepareStatement(
                "UPDATE messages SET is_read = 1 WHERE recipient = ? AND sender = ? AND group_id = 0 AND is_read = 0")) {
            ps.setString(1, me.toString());
            ps.setString(2, other.toString());
            ps.executeUpdate();
        }
        return new ThreadResult(otherName, List.of(me, other), List.of(), messages);
    }

    private static ThreadResult groupThread(Connection tx, UUID me, long group) throws SQLException {
        if (!isMember(tx, group, me)) return null;
        String name = groupName(tx, group);
        List<UUID> members = members(tx, group);
        List<String> memberNames = new ArrayList<>();
        for (UUID u : members) {
            String n = PhoneDatabase.nameSync(tx, u);
            if (n != null) memberNames.add(n);
        }
        memberNames.sort(String.CASE_INSENSITIVE_ORDER);
        List<S2C.MessageEntry> messages = new ArrayList<>();
        long maxId = 0;
        try (PreparedStatement ps = tx.prepareStatement("""
                SELECT m.id, m.sender, m.body, m.ts, m.photo_id, a.name FROM messages m
                LEFT JOIN accounts a ON a.uuid = m.sender
                WHERE m.group_id = ? ORDER BY m.id DESC LIMIT ?""")) {
            ps.setLong(1, group);
            ps.setInt(2, THREAD_LIMIT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    maxId = Math.max(maxId, rs.getLong(1));
                    boolean mine = me.toString().equals(rs.getString(2));
                    String sender = rs.getString(6);
                    messages.add(new S2C.MessageEntry(mine, sender == null ? "?" : sender, rs.getString(3), rs.getLong(5), rs.getLong(4)));
                }
            }
        }
        Collections.reverse(messages);
        try (PreparedStatement ps = tx.prepareStatement(
                "UPDATE group_members SET last_read = MAX(last_read, ?) WHERE group_id = ? AND uuid = ?")) {
            ps.setLong(1, maxId);
            ps.setLong(2, group);
            ps.setString(3, me.toString());
            ps.executeUpdate();
        }
        return new ThreadResult(name, members, memberNames, messages);
    }

    // ---------------------------------------------------------------- sending

    private record Stored(String title, List<UUID> recipients, boolean photoOk) {}

    public static void send(ServerPlayer sender, Conv conv, String rawBody, long photoId) {
        var cfg = PhoneServer.config();
        UUID me = sender.getUUID();
        if (!conv.isGroup() && conv.player().equals(me)) return;
        String body = sanitize(rawBody, cfg.maxMessageLength);
        if (body.isEmpty() && photoId <= 0) return;
        if (!Cooldowns.tryUse(me, "message", cfg.messageCooldownMs)) {
            sender.sendOverlayMessage(Component.translatable("smartphone.error.slow_down").withStyle(ChatFormatting.YELLOW));
            return;
        }

        long now = System.currentTimeMillis();
        PhoneServer.then(PhoneServer.db().transaction(tx -> {
            if (photoId > 0 && !PhotoService.ownsPhoto(tx, me, photoId)) return null; // only your own photos
            List<UUID> recipients;
            String title;
            if (conv.isGroup()) {
                if (!isMember(tx, conv.group(), me)) return null;
                title = groupName(tx, conv.group());
                recipients = members(tx, conv.group());
                recipients.remove(me);
            } else {
                title = PhoneDatabase.nameSync(tx, conv.player());
                if (title == null) return null; // nobody with that UUID has ever played here
                recipients = List.of(conv.player());
            }
            try (PreparedStatement ps = tx.prepareStatement(
                    "INSERT INTO messages(sender, recipient, body, ts, is_read, group_id, photo_id) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, me.toString());
                ps.setString(2, conv.isGroup() ? "" : conv.player().toString());
                ps.setString(3, body);
                ps.setLong(4, now);
                ps.setInt(5, 0);
                ps.setLong(6, conv.isGroup() ? conv.group() : 0);
                ps.setLong(7, Math.max(0, photoId));
                ps.executeUpdate();
            }
            if (conv.isGroup()) { // the sender has obviously read their own message
                try (PreparedStatement ps = tx.prepareStatement(
                        "UPDATE group_members SET last_read = last_insert_rowid() WHERE group_id = ? AND uuid = ?")) {
                    ps.setLong(1, conv.group());
                    ps.setString(2, me.toString());
                    ps.executeUpdate();
                }
            }
            return new Stored(title, recipients, true);
        }), sender, (s, stored) -> {
            if (stored == null) return;
            openThread(s, conv); // refresh the sender's own conversation view
            // Deliver live to whoever is online; the rest find it at their next login.
            for (UUID r : stored.recipients()) {
                ServerPlayer target = PhoneServer.online(r);
                if (target != null) {
                    Conv theirs = conv.isGroup() ? conv : Conv.direct(me);
                    ServerPlayNetworking.send(target, new S2C.NewMessage(theirs, s.getName().getString(),
                            conv.isGroup() ? stored.title() : "", body, photoId > 0, now));
                }
            }
        });
    }

    // ---------------------------------------------------------------- groups

    public static void createGroup(ServerPlayer player, String rawName, List<UUID> invited) {
        var cfg = PhoneServer.config();
        String name = sanitize(rawName, 24);
        if (name.isEmpty()) return;
        if (!Cooldowns.tryUse(player.getUUID(), "group", 2000)) return;
        UUID me = player.getUUID();
        Set<UUID> members = new LinkedHashSet<>();
        members.add(me);
        for (UUID u : invited) {
            if (members.size() >= cfg.maxGroupMembers) break;
            members.add(u);
        }
        PhoneServer.then(PhoneServer.db().transaction(tx -> {
            try (PreparedStatement ps = tx.prepareStatement("SELECT COUNT(*) FROM group_members WHERE uuid = ?")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= MAX_GROUPS_PER_PLAYER) return -1L;
                }
            }
            long id;
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO chat_groups(name, owner, created) VALUES (?,?,?)")) {
                ps.setString(1, name);
                ps.setString(2, me.toString());
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("SELECT last_insert_rowid()"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
            for (UUID u : members) {
                if (PhoneDatabase.nameSync(tx, u) == null) continue; // only real players
                try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO group_members(group_id, uuid) VALUES (?,?)")) {
                    ps.setLong(1, id);
                    ps.setString(2, u.toString());
                    ps.executeUpdate();
                }
            }
            return id;
        }), player, (p, id) -> {
            if (id < 0) {
                p.sendOverlayMessage(Component.translatable("smartphone.messages.error.too_many_groups").withStyle(ChatFormatting.RED));
                return;
            }
            for (UUID u : members) {
                if (u.equals(me)) continue;
                PhoneServer.notify(u, "messages", Component.translatable("smartphone.notify.group_added_title", name),
                        Component.translatable("smartphone.notify.group_added_body", p.getName()));
            }
            sendContacts(p);
            openThread(p, Conv.group(id));
        });
    }

    /** Add someone to a group you are in, or leave a group (add=false). */
    public static void membership(ServerPlayer player, long group, UUID member, boolean add) {
        UUID me = player.getUUID();
        int max = PhoneServer.config().maxGroupMembers;
        PhoneServer.then(PhoneServer.db().transaction(tx -> {
            if (!isMember(tx, group, me)) return null;
            String name = groupName(tx, group);
            if (add) {
                if (PhoneDatabase.nameSync(tx, member) == null || members(tx, group).size() >= max) return null;
                try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO group_members(group_id, uuid, last_read) VALUES (?,?,?)")) {
                    ps.setLong(1, group);
                    ps.setString(2, member.toString());
                    ps.setLong(3, 0);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = tx.prepareStatement("DELETE FROM group_members WHERE group_id = ? AND uuid = ?")) {
                    ps.setLong(1, group);
                    ps.setString(2, me.toString());
                    ps.executeUpdate();
                }
                if (members(tx, group).isEmpty()) { // last one out removes the group
                    try (PreparedStatement ps = tx.prepareStatement("DELETE FROM chat_groups WHERE id = ?")) {
                        ps.setLong(1, group);
                        ps.executeUpdate();
                    }
                }
            }
            return name;
        }), player, (p, name) -> {
            if (name == null) return;
            if (add) {
                PhoneServer.notify(member, "messages", Component.translatable("smartphone.notify.group_added_title", name),
                        Component.translatable("smartphone.notify.group_added_body", p.getName()));
                openThread(p, Conv.group(group));
            } else {
                sendContacts(p);
            }
        });
    }

    /** Strips formatting codes and control characters, collapses whitespace and enforces the length limit. */
    static String sanitize(String raw, int maxLength) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLength));
        boolean lastSpace = false;
        for (int i = 0; i < raw.length() && sb.length() < maxLength; i++) {
            char ch = raw.charAt(i);
            if (ch == '§') { i++; continue; } // § colour/format code + its argument
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                if (!lastSpace && sb.length() > 0) sb.append(' ');
                lastSpace = true;
                continue;
            }
            sb.append(ch);
            lastSpace = false;
        }
        return sb.toString().strip();
    }
}
