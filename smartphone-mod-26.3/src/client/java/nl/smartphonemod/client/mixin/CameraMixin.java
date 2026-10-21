package nl.smartphonemod.client.mixin;

import nl.smartphonemod.client.CameraMode;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera zoom: narrows the field of view while the walking camera is zoomed in. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void smartphone$zoom(float partialTicks, CallbackInfoReturnable<Float> cir) {
        float f = CameraMode.fovFactor() * nl.smartphonemod.client.cctv.CctvView.fovFactor();
        if (f != 1f) cir.setReturnValue(cir.getReturnValue() * f);
    }
}
