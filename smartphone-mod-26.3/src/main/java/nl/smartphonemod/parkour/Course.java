package nl.smartphonemod.parkour;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * A parkour course made of ordinary blocks: a start, ordered checkpoints and a finish. Stored as JSON in
 * the database (so field names are part of the save format).
 */
public final class Course {
    /** A marked block and the direction players face when they are put back on it. */
    public static final class Marker {
        public int x, y, z;
        public float yaw;

        public Marker() {}

        public Marker(BlockPos pos, float yaw) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.yaw = yaw;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public long packed() {
            return BlockPos.asLong(x, y, z);
        }
    }

    /** Lowercase id used in commands. */
    public String name;
    /** Name as typed when created (shown to players). */
    public String display;
    public String dimension = "minecraft:overworld";
    public Marker start;
    public List<Marker> checkpoints = new ArrayList<>();
    public Marker finish;
    /** Falling below this Y puts you back; null = automatic (3 below the lowest marker). */
    public Integer resetY;
    /** Touching water puts you back (lava always does). */
    public boolean waterReset = true;
    /** Leaderboard text display, if any. */
    public String hologram;
    public String hologramDim;
    /** First-completion reward: item stacks (JSON, ItemStack codec) and/or bank money. */
    public List<String> rewardItems = new ArrayList<>();
    public long rewardMoney;
    /** Protected box {minX, minY, minZ, maxX, maxY, maxZ}, or null. Markers are always protected. */
    public int[] protect;

    public boolean inProtected(BlockPos p) {
        int[] b = protect;
        return b != null && p.getX() >= b[0] && p.getY() >= b[1] && p.getZ() >= b[2]
                && p.getX() <= b[3] && p.getY() <= b[4] && p.getZ() <= b[5];
    }

    public boolean hasReward() {
        return rewardMoney > 0 || (rewardItems != null && !rewardItems.isEmpty());
    }

    public boolean playable() {
        return start != null && finish != null;
    }

    public List<Marker> allMarkers() {
        List<Marker> out = new ArrayList<>();
        if (start != null) out.add(start);
        out.addAll(checkpoints);
        if (finish != null) out.add(finish);
        return out;
    }

    public int effectiveResetY() {
        if (resetY != null) return resetY;
        int min = Integer.MAX_VALUE;
        for (Marker m : allMarkers()) min = Math.min(min, m.y);
        return min == Integer.MAX_VALUE ? Integer.MIN_VALUE : min - 3;
    }
}
