package nl.smartphonemod.network;

import nl.smartphonemod.SmartphoneMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Version handshake. The protocol number is part of the channel name, so a client with a different
 * version of the mod simply does not have this channel. The server notices during login and turns the
 * player away with a clear message, instead of letting them in and crashing on the first phone packet.
 * Bump {@link #VERSION} whenever any payload changes.
 */
public final class Protocol {
    public static final int VERSION = 7;

    public record Check() implements CustomPacketPayload {
        public static final Check INSTANCE = new Check();
        public static final Type<Check> ID = new Type<>(SmartphoneMod.id("protocol_" + VERSION));
        public static final StreamCodec<FriendlyByteBuf, Check> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    private Protocol() {}

    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer(SmartphoneMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    public static void register() {
        PayloadTypeRegistry.clientboundConfiguration().register(Check.ID, Check.CODEC);
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (!ServerConfigurationNetworking.canSend(handler, Check.ID)) {
                // Sent as a literal: the client may not have (the same) translations.
                handler.disconnect(Component.literal("Smartphone mod\n\n").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("NL: Deze server gebruikt Smartphone " + modVersion()
                                + ". Installeer precies deze versie van de mod.\n\n").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal("EN: This server runs Smartphone " + modVersion()
                                + ". Please install exactly this version of the mod.").withStyle(ChatFormatting.GRAY)));
            }
        });
    }
}
