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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 临时传送门：领域之主进出世界时，在两端各开一个原版下界传送门样式的门，{@link #LIFE_TICKS} tick 后自动消失。
 *
 * <p>两个实现要点：</p>
 * <ul>
 *   <li><b>必须连框架一起搭。</b>单放 {@code minecraft:nether_portal} 方块而没有黑曜石框，
 *       它会在下一次方块更新时被原版判定为「无框架」直接崩掉。</li>
 *   <li><b>底框允许压在地面上。</b>门是立在地上的，底框那一行必然和地面重合，
 *       所以这里记录每个格子原来的方块，到期时原样还原——不会在主世界挖出洞，也不会给场地留疤。</li>
 * </ul>
 *
 * <p>门朝 X 轴方向（宽 2、高 3）。门内 2×3 必须可放置，否则静默放弃：
 * 宁可不出现，也不要把门塞进墙里。</p>
 */
public final class DomainPortal {

    /** 存在时长（5 秒）。 */
    public static final int LIFE_TICKS = 100;

    private static final int INNER_WIDTH = 2;
    private static final int INNER_HEIGHT = 3;

    private static final class Entry {
        private final ServerLevel level;
        private final Map<BlockPos, BlockState> previous;
        private int ticksLeft;

        private Entry(ServerLevel level, Map<BlockPos, BlockState> previous, int ticksLeft) {
            this.level = level;
            this.previous = previous;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private DomainPortal() {
    }

    /** 在 {@code pos}（脚踩位置）处开一扇临时门；空间不足或已有门占位则静默跳过。 */
    public static void open(ServerLevel level, Vec3 pos) {
        BlockPos base = BlockPos.containing(pos);
        if (isOccupied(level, base)) {
            return;
        }

        List<BlockPos> frame = new ArrayList<>();
        List<BlockPos> inner = new ArrayList<>();
        for (int dx = -1; dx <= INNER_WIDTH; dx++) {
            for (int dy = -1; dy <= INNER_HEIGHT; dy++) {
                BlockPos p = base.offset(dx, dy, 0);
                boolean inInner = dx >= 0 && dx < INNER_WIDTH && dy >= 0 && dy < INNER_HEIGHT;
                if (inInner) {
                    inner.add(p);
                } else {
                    frame.add(p);
                }
            }
        }

        // 门内必须是可放置的方块；框架除了最底一行可以压地面，其余同样要空着
        for (BlockPos p : inner) {
            if (!level.getBlockState(p).canBeReplaced()) {
                return;
            }
        }
        for (BlockPos p : frame) {
            boolean bottomRow = p.getY() == base.getY() - 1;
            if (!bottomRow && !level.getBlockState(p).canBeReplaced()) {
                return;
            }
        }

        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X);

        Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
        for (BlockPos p : frame) {
            previous.put(p, level.getBlockState(p));
            level.setBlock(p, obsidian, 2);
        }
        for (BlockPos p : inner) {
            previous.put(p, level.getBlockState(p));
            level.setBlock(p, portal, 2);
        }

        level.playSound(null, base, SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 0.8F, 0.9F);
        ENTRIES.add(new Entry(level, previous, LIFE_TICKS));
    }

    private static boolean isOccupied(ServerLevel level, BlockPos base) {
        for (Entry entry : ENTRIES) {
            if (entry.level != level) {
                continue;
            }
            for (BlockPos p : entry.previous.keySet()) {
                if (p.getX() == base.getX() && p.getZ() == base.getZ()
                        && Math.abs(p.getY() - base.getY()) <= INNER_HEIGHT + 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 每 tick 递减存活时间，到期把门拆掉并还原地面。 */
    public static void tick(MinecraftServer server) {
        if (ENTRIES.isEmpty()) {
            return;
        }
        Iterator<Entry> it = ENTRIES.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (--entry.ticksLeft > 0) {
                continue;
            }
            BlockPos soundAt = null;
            for (Map.Entry<BlockPos, BlockState> cell : entry.previous.entrySet()) {
                BlockPos p = cell.getKey();
                BlockState current = entry.level.getBlockState(p);
                // 只还原还是传送门/黑曜石的格子，避免覆盖玩家后来放上去的东西
                if (current.is(Blocks.NETHER_PORTAL) || current.is(Blocks.OBSIDIAN)) {
                    entry.level.setBlock(p, cell.getValue(), 2);
                }
                if (soundAt == null) {
                    soundAt = p;
                }
            }
            if (soundAt != null) {
                entry.level.playSound(null, soundAt, SoundEvents.PORTAL_TRAVEL,
                        SoundSource.HOSTILE, 0.5F, 1.6F);
            }
            it.remove();
        }
    }

    /** 服务器停服或维度崩溃时紧急清场。 */
    public static void clearAll() {
        for (Entry entry : ENTRIES) {
            for (Map.Entry<BlockPos, BlockState> cell : entry.previous.entrySet()) {
                BlockState current = entry.level.getBlockState(cell.getKey());
                if (current.is(Blocks.NETHER_PORTAL) || current.is(Blocks.OBSIDIAN)) {
                    entry.level.setBlock(cell.getKey(), cell.getValue(), 2);
                }
            }
        }
        ENTRIES.clear();
    }
}
