package nl.smartphonemod.cctv;

import nl.smartphonemod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

/** A security camera: where it looks (world yaw/pitch), its name, who placed it and whether someone watches. */
public class CctvCameraBlockEntity extends BlockEntity {
    private float yaw, pitch;
    private String name = "";
    private UUID owner;
    private int viewers;

    public CctvCameraBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CCTV_CAMERA, pos, state);
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public String name() {
        return name;
    }

    public String displayName() {
        return name.isBlank() ? "Camera " + worldPosition.getX() + ", " + worldPosition.getY() + ", " + worldPosition.getZ() : name;
    }

    public UUID owner() {
        return owner;
    }

    public int viewers() {
        return viewers;
    }

    private AttachFace face() {
        BlockState s = getBlockState();
        return s.hasProperty(CctvCameraBlock.FACE) ? s.getValue(CctvCameraBlock.FACE) : AttachFace.WALL;
    }

    private Direction facing() {
        BlockState s = getBlockState();
        return s.hasProperty(CctvCameraBlock.FACING) ? s.getValue(CctvCameraBlock.FACING) : Direction.NORTH;
    }

    /** Pitch range depends on how the camera is mounted. */
    public float minPitch() {
        return switch (face()) {
            case CEILING -> 5;
            case FLOOR -> -60;
            default -> -40;
        };
    }

    public float maxPitch() {
        return switch (face()) {
            case CEILING -> 89;
            case FLOOR -> 35;
            default -> 75;
        };
    }

    /** Wall cameras can't look into the wall: yaw stays within 80° of the direction they face. */
    public float clampYaw(float y) {
        y = Mth.wrapDegrees(y);
        if (face() != AttachFace.WALL) return y;
        float base = facing().toYRot();
        float d = Mth.wrapDegrees(y - base);
        return Mth.wrapDegrees(base + Mth.clamp(d, -80f, 80f));
    }

    public void setView(float newYaw, float newPitch) {
        float y = clampYaw(newYaw), p = Mth.clamp(newPitch, minPitch(), maxPitch());
        if (Math.abs(Mth.wrapDegrees(y - yaw)) < 0.01f && Math.abs(p - pitch) < 0.01f) return;
        yaw = y;
        pitch = p;
        sync();
    }

    public void setName(String n) {
        name = n == null ? "" : n.strip();
        if (name.length() > 24) name = name.substring(0, 24);
        sync();
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
        setChanged();
    }

    public void setViewers(int v) {
        if (v == viewers) return;
        viewers = v;
        sync();
    }

    /** Initial direction when placed. */
    public void initFrom(float playerYaw) {
        switch (face()) {
            case WALL -> { yaw = facing().toYRot(); pitch = 20; }
            case CEILING -> { yaw = Mth.wrapDegrees(playerYaw + 180); pitch = 40; }
            case FLOOR -> { yaw = Mth.wrapDegrees(playerYaw + 180); pitch = 0; }
        }
        setChanged();
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // client-side prediction for the person steering the camera
    public void setViewClient(float y, float p) {
        yaw = clampYaw(y);
        pitch = Mth.clamp(p, minPitch(), maxPitch());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putFloat("Yaw", yaw);
        output.putFloat("Pitch", pitch);
        output.putString("CamName", name);
        output.putInt("Viewers", viewers);
        if (owner != null) output.store("Owner", UUIDUtil.CODEC, owner);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        yaw = input.getFloatOr("Yaw", 0);
        pitch = input.getFloatOr("Pitch", 0);
        name = input.getStringOr("CamName", "");
        viewers = input.getIntOr("Viewers", 0);
        owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) CctvService.cameraRemoved(serverLevel, pos);
    }
}
