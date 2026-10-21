package nl.smartphonemod.server;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** The 112 button: shares your location with everyone online, or only with admins (config). */
public final class EmergencyService {
    private EmergencyService() {}

    public static void trigger(ServerPlayer player) {
        var cfg = PhoneServer.config();
        if (!Cooldowns.tryUse(player.getUUID(), "emergency", cfg.emergencyCooldownSeconds * 1000L)) {
            player.sendOverlayMessage(Component.translatable("smartphone.emergency.cooldown", cfg.emergencyCooldownSeconds)
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        boolean adminsOnly = "admins".equalsIgnoreCase(cfg.emergencyTarget);
        String dim = player.level().dimension().identifier().toString();
        int x = player.getBlockX(), y = player.getBlockY(), z = player.getBlockZ();
        var alert = new S2C.EmergencyAlert(player.getUUID(), player.getName().getString(), x, y, z, dim);
        Component chat = Component.translatable("smartphone.emergency.chat", player.getName(), x, y, z, dim)
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);

        int reached = 0;
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
            if (other == player) continue;
            if (adminsOnly && !other.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) continue;
            ServerPlayNetworking.send(other, alert);
            other.sendSystemMessage(chat);
            reached++;
        }
        SmartphoneMod.LOGGER.warn("112 alert from {} at {} {} {} in {} ({} players notified)",
                player.getName().getString(), x, y, z, dim, reached);
        ServerPlayNetworking.send(player, new S2C.Notify("emergency",
                Component.translatable("smartphone.emergency.sent_title"),
                Component.translatable(adminsOnly ? "smartphone.emergency.sent_admins" : "smartphone.emergency.sent_all", reached)));
    }
}
