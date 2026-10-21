package nl.smartphonemod.client.cctv;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.cctv.CctvCameraBlockEntity;
import nl.smartphonemod.registry.ModBlocks;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Draws the camera head (an item model) turned to where the camera looks; the red lamp blinks while watched. */
public class CctvCameraRenderer implements BlockEntityRenderer<CctvCameraBlockEntity, CctvCameraRenderer.State> {
    public static class State extends BlockEntityRenderState {
        float yaw, pitch;
        final ItemStackRenderState head = new ItemStackRenderState();
    }

    /** Created lazily: item stacks can't be made before the data components are bound. */
    private static ItemStack head, headLive;

    private static ItemStack head(boolean live) {
        if (head == null) {
            head = new ItemStack(ModBlocks.CCTV_ITEM);
            head.set(DataComponents.ITEM_MODEL, SmartphoneMod.id("cctv_head"));
            headLive = new ItemStack(ModBlocks.CCTV_ITEM);
            headLive.set(DataComponents.ITEM_MODEL, SmartphoneMod.id("cctv_head_live"));
        }
        return live ? headLive : head;
    }

    private final ItemModelResolver items;

    public CctvCameraRenderer(BlockEntityRendererProvider.Context context) {
        this.items = context.itemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(CctvCameraBlockEntity be, State state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, cameraPosition, breakProgress);
        state.yaw = be.yaw();
        state.pitch = be.pitch();
        boolean lamp = be.viewers() > 0 && (System.currentTimeMillis() / 500) % 2 == 0;
        items.updateForTopItem(state.head, head(lamp), ItemDisplayContext.FIXED, be.getLevel(), null, 0);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.rotate(Axis.YP.rotationDegrees(-state.yaw));
        pose.rotate(Axis.XP.rotationDegrees(state.pitch));
        state.head.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }
}
