package nl.smartphonemod.client.mixin;

import nl.smartphonemod.client.CameraMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In the walking camera, left click is the shutter and right click goes back to the phone. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void smartphone$shutter(CallbackInfoReturnable<Boolean> cir) {
        if (CameraMode.active()) {
            CameraMode.shutter();
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void smartphone$noMining(boolean down, CallbackInfo ci) {
        if (CameraMode.active()) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void smartphone$backToPhone(CallbackInfo ci) {
        if (CameraMode.active()) {
            CameraMode.exitToPhone();
            ci.cancel();
        }
    }
}
