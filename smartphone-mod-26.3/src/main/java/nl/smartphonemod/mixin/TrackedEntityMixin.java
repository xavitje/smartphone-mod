package nl.smartphonemod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import nl.smartphonemod.cctv.CctvService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Entities are shown to a camera watcher based on the distance to the camera, not to their body. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @ModifyExpressionValue(method = "updatePlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 smartphone$cctvEyes(Vec3 original, @Local(argsOnly = true) ServerPlayer player) {
        CctvService.Session s = CctvService.session(player);
        return s == null || s.level() != player.level() ? original : s.eye();
    }
}
