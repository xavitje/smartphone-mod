package nl.smartphonemod.client.mixin;

import nl.smartphonemod.client.CameraMode;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The hand (holding the phone) is not drawn while you look through the phone's camera. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void smartphone$hideHand(CallbackInfo ci) {
        if (CameraMode.active() || nl.smartphonemod.client.cctv.CctvView.active()) ci.cancel();
    }
}
