package nl.smartphonemod.mixin;

import nl.smartphonemod.cctv.CctvRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block changes near security cameras go into the 30-second buffer. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void smartphone$cctvBlock(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
        CctvRecorder.blockChanged((ServerLevel) (Object) this, pos, old, current);
    }
}
