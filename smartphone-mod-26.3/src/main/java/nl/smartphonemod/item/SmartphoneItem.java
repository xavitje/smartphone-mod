package nl.smartphonemod.item;

import nl.smartphonemod.server.PhoneServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Right-click opens the phone. The client never opens the screen by itself: the server
 * validates that the phone is really in hand and then sends an OpenPhone packet.
 */
public class SmartphoneItem extends Item {
    public SmartphoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            PhoneServer.openPhone(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }
}
