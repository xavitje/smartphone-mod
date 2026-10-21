package nl.smartphonemod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import nl.smartphonemod.cctv.CctvService;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While a player watches a security camera, the chunks around the camera are the ones sent to them. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @ModifyExpressionValue(method = "updateChunkTracking",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"))
    private ChunkPos smartphone$cctvCenter(ChunkPos original, @Local(argsOnly = true) ServerPlayer player) {
        CctvService.Session s = CctvService.session(player);
        return s == null || s.level() != player.level() ? original : s.chunk();
    }

    @ModifyReturnValue(method = "getPlayerViewDistance", at = @At("RETURN"))
    private int smartphone$cctvDistance(int original, @Local(argsOnly = true) ServerPlayer player) {
        return CctvService.session(player) == null ? original : Math.min(original, CctvService.viewRadius());
    }
}
