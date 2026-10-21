package nl.smartphonemod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import nl.smartphonemod.client.cctv.CctvReplay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** During a CCTV replay the recorded stand-in entities are drawn instead of the live ones. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @WrapOperation(method = "extractVisibleEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private Iterable<Entity> smartphone$replayEntities(ClientLevel level, Operation<Iterable<Entity>> original) {
        return CctvReplay.active() ? CctvReplay.entities() : original.call(level);
    }
}
