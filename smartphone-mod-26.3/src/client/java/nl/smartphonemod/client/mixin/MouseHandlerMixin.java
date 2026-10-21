package nl.smartphonemod.client.mixin;

import nl.smartphonemod.client.CameraMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the walking camera the scroll wheel zooms instead of changing the hotbar slot. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void smartphone$zoom(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        if (CameraMode.active() && Minecraft.getInstance().gui.screen() == null) {
            CameraMode.scroll(yoffset);
            ci.cancel();
        }
    }
}
