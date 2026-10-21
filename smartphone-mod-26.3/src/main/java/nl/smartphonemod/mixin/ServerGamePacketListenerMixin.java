package nl.smartphonemod.mixin;

import nl.smartphonemod.parkour.ParkourService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** Tells the parkour about real teleports (commands, ender pearls, chorus fruit, other mods). */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("HEAD"))
    private void smartphone$teleport(PositionMoveRotation destination, Set<Relative> relatives, CallbackInfo ci) {
        PositionMoveRotation from = PositionMoveRotation.of(player);
        PositionMoveRotation to = PositionMoveRotation.calculateAbsolute(from, destination, relatives);
        ParkourService.onTeleport(player, from.position(), to.position());
    }
}
