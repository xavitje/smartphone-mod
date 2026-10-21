package nl.smartphonemod.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/** A conversation: either with one player, or in a group chat ({@code group > 0}). */
public record Conv(UUID player, long group) {
    public static final UUID NONE = new UUID(0, 0);
    public static final StreamCodec<RegistryFriendlyByteBuf, Conv> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, Conv::player,
            ByteBufCodecs.VAR_LONG, Conv::group,
            Conv::new);

    public static Conv direct(UUID player) {
        return new Conv(player, 0);
    }

    public static Conv group(long id) {
        return new Conv(NONE, id);
    }

    public boolean isGroup() {
        return group > 0;
    }
}
