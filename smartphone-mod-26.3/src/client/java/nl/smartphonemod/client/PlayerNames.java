package nl.smartphonemod.client;

import nl.smartphonemod.network.S2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Names for the player search: who is online right now (tab list) first, then known contacts. */
public final class PlayerNames {
    public record Entry(UUID uuid, String name, boolean online) {}

    private PlayerNames() {}

    /** All players matching {@code query} (case-insensitive, prefix matches first), excluding yourself. */
    public static List<Entry> search(String query, int limit) {
        Minecraft mc = Minecraft.getInstance();
        UUID self = mc.player == null ? null : mc.player.getUUID();
        Map<String, Entry> all = new LinkedHashMap<>();
        if (mc.getConnection() != null) {
            List<PlayerInfo> online = new ArrayList<>(mc.getConnection().getOnlinePlayers());
            online.sort((a, b) -> a.getProfile().name().compareToIgnoreCase(b.getProfile().name()));
            for (PlayerInfo info : online) {
                if (info.getProfile().id().equals(self)) continue;
                String name = info.getProfile().name();
                all.put(name.toLowerCase(Locale.ROOT), new Entry(info.getProfile().id(), name, true));
            }
        }
        if (ClientPhoneState.contacts != null) {
            for (S2C.ContactEntry c : ClientPhoneState.contacts) {
                all.putIfAbsent(c.name().toLowerCase(Locale.ROOT), new Entry(c.uuid(), c.name(), c.online()));
            }
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> prefix = new ArrayList<>(), contains = new ArrayList<>();
        for (Map.Entry<String, Entry> e : all.entrySet()) {
            if (q.isEmpty() || e.getKey().startsWith(q)) prefix.add(e.getValue());
            else if (e.getKey().contains(q)) contains.add(e.getValue());
        }
        prefix.addAll(contains);
        return prefix.size() > limit ? prefix.subList(0, limit) : prefix;
    }
}
