package com.smallworld.domain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 临时传送门：领域之主进出世界时，在两端各开一个原版下界传送门样式的门，{@link #LIFE_TICKS} tick 后自动消失。
 *
 * <p>为什么要自己搭方框：单独放 {@code minecraft:nether_portal} 方块而没有黑曜石框架，
 * 它会在下一次方块更新时被原版逻辑判定为「无框架」直接崩掉。所以这里连框架一起搭，
 * 到期时只回收**我们放下的那些方块**（如果那块已经被别的东西覆盖就跳过，不误删）。</p>
 *
 * <p>门开在 X 轴方向（宽 2、高 3），要求整个 4×5 区域都是空气才搭，否则直接放弃——
 * 宁可不出现，也不要在主世界拆别人的建筑。</p>
 */
public final class DomainPortal {

    /** 存在时长（5 秒）。 */
    public static final int LIFE_TICKS = 100;

    private static final int INNER_WIDTH = 2;
    private static final int INNER_HEIGHT = 3;

    private static final class Entry {
        private final ServerLevel level;
        private final List<BlockPos> placed;
        private int ticksLeft;

        private Entry(ServerLevel level, List<BlockPos> placed, int ticksLeft) {
            this.level = level;
            this.placed = placed;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private DomainPortal() {
    }

    /** 在 {@code pos}（脚踩位置）处开一扇临时门；空间不足或已有门占位则静默跳过。 */
    public static void open(ServerLevel level, Vec3 pos) {
        BlockPos base = BlockPos.containing(pos);
        if (isOccupied(base)) {
            return;
        }
        List<BlockPos> frame = new ArrayList<>();
        List<BlockPos> inner = new ArrayList<>();

        for (int dx = -1; dx <= INNER_WIDTH; dx++) {
            for (int dy = -1; dy <= INNER_HEIGHT; dy++) {
                BlockPos p = base.offset(dx, dy, 0);
                boolean isInner = dx >= 0 && dx < INNER_WIDTH && dy >= 0 && dy < INNER_HEIGHT;
                if (isInner) {
                    inner.add(p);
                } else {
                    frame.add(p);
                }
            }
        }

        for (BlockPos p : frame) {
            if (!level.getBlockState(p).canBeReplaced()) {
                return;
            }
        }
        for (BlockPos p : inner) {
            if (!level.getBlockState(p).canBeReplaced()) {
                return;
            }
        }

        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X);

        List<BlockPos> placed = new ArrayList<>(frame.size() + inner.size());
        for (BlockPos p : frame) {
            level.setBlock(p, obsidian, 2);
            placed.add(p);
        }
        for (BlockPos p : inner) {
            level.setBlock(p, portal, 2);
            placed.add(p);
        }

        level.playSound(null, base, SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 0.8F, 0.9F);
        ENTRIES.add(new Entry(level, placed, LIFE_TICKS));
    }

    private static boolean isOccupied(BlockPos base) {
        for (Entry entry : ENTRIES) {
            for (BlockPos p : entry.placed) {
                if (p.getX() == base.getX() && p.getZ() == base.getZ()
                        && Math.abs(p.getY() - base.getY()) <= INNER_HEIGHT + 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 每 tick 递减存活时间，到期把门拆掉。 */
    public static void tick(MinecraftServer server) {
        if (ENTRIES.isEmpty()) {
            return;
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        Iterator<Entry> it = ENTRIES.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (--entry.ticksLeft > 0) {
                continue;
            }
            for (BlockPos p : entry.placed) {
                BlockState current = entry.level.getBlockState(p);
                // 只回收还是传送门/黑曜石的格子，避免误删后来盖上去的方块
                if (current.is(Blocks.NETHER_PORTAL) || current.is(Blocks.OBSIDIAN)) {
                    entry.level.setBlock(p, air, 2);
                }
            }
            entry.level.playSound(null, entry.placed.get(0), SoundEvents.PORTAL_TRAVEL,
                    SoundSource.HOSTILE, 0.5F, 1.6F);
            it.remove();
        }
    }

    /** 服务器停服或维度崩溃时紧急清场。 */
    public static void clearAll() {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (Entry entry : ENTRIES) {
            for (BlockPos p : entry.placed) {
                BlockState current = entry.level.getBlockState(p);
                if (current.is(Blocks.NETHER_PORTAL) || current.is(Blocks.OBSIDIAN)) {
                    entry.level.setBlock(p, air, 2);
                }
            }
        }
        ENTRIES.clear();
    }
}
