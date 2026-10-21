package nl.smartphonemod.cctv;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Security camera. Attaches to a floor, wall or ceiling like a lever. Right-click (with the manage permission)
 * turns it 22.5°, sneak + right-click tilts it. Watching happens through the phone's CCTV app.
 */
public class CctvCameraBlock extends FaceAttachedHorizontalDirectionalBlock implements EntityBlock {
    private static final VoxelShape SHAPE = Block.box(4, 4, 4, 12, 12, 12);

    public CctvCameraBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FACE, AttachFace.WALL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FACE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CctvCameraBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof CctvCameraBlockEntity cam) {
            cam.initFrom(placer == null ? 0 : placer.getYRot());
            if (placer instanceof Player p) cam.setOwner(p.getUUID());
            var custom = stack.get(DataComponents.CUSTOM_NAME);
            if (custom != null) cam.setName(custom.getString());
            CctvService.cameraPlaced(serverLevel, cam);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !(level.getBlockEntity(pos) instanceof CctvCameraBlockEntity cam)) {
            return InteractionResult.SUCCESS;
        }
        if (!CctvPerms.canManage(sp)) {
            sp.sendOverlayMessage(Component.translatableWithFallback("smartphone.cctv.no_manage",
                    "Alleen beheerders kunnen deze camera draaien.").withStyle(ChatFormatting.RED));
            return InteractionResult.SUCCESS;
        }
        if (sp.isShiftKeyDown()) {
            float p = cam.pitch() + 15;
            if (p > cam.maxPitch() + 0.1f) p = cam.minPitch();
            cam.setView(cam.yaw(), p);
        } else {
            float y = cam.yaw() + 22.5f;
            float clamped = cam.clampYaw(y);
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(clamped - y)) > 0.1f) {
                // Wall camera hit its limit: jump back to the other end.
                clamped = cam.clampYaw(state.getValue(FACING).toYRot() - 80);
            }
            cam.setView(clamped, cam.pitch());
        }
        level.playSound(null, pos, SoundEvents.DISPENSER_DISPENSE, SoundSource.BLOCKS, 0.3f, 1.8f);
        sp.sendOverlayMessage(Component.literal("↻ " + cam.displayName() + "  " + Math.round(cam.yaw()) + "° / " + Math.round(cam.pitch()) + "°")
                .withStyle(ChatFormatting.GRAY));
        return InteractionResult.SUCCESS;
    }
}
