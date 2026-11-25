package com.smallworld.domain;

import com.smallworld.entity.DomainLord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 在微型维度里就地「整」出一座场地。
 *
 * <p>这个维度不是空世界了——它有一整层实心黑石地面（见 data/smallworld/dimension/domain.json），
 * 所以场地是<b>贴着地表铺出来</b>的，不存在悬空平台：玩家进来看到的是延伸到天边的黑石荒原，
 * 中间一片打磨过的圆形场地。</p>
 *
 * <p>中心故意留空（3 格高），领域之主就传送到那里，不会卡在祭坛方块里。</p>
 *
 * <p>搭建是幂等的：重复调用只会覆盖成同样的方块，所以每个领域会话直接重建，不做持久化。</p>
 */
public final class DomainArena {

    /** 场地半径（比领域判定半径略大，避免玩家站在边缘被判定到外面）。 */
    public static final int RADIUS = DomainLord.DOMAIN_RADIUS + 5;
    private static final int WALL_HEIGHT = 8;
    /** 场地外围额外装饰的半径。 */
    private static final int DECOR_RADIUS = RADIUS + 26;

    private DomainArena() {
    }

    /**
     * 求某个水平坐标处的地表高度（脚下方块之上那一格，也就是「脚踩的高度」）。
     *
     * <p>先强制加载区块，否则未生成的区块会让 heightmap 返回世界最低点。</p>
     */
    public static int surfaceY(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /**
     * 在 {@code feet}（脚踩的那一格）周围铺出场地。调用方应保证 {@code feet.getY()}
     * 是该处的地表高度（用 {@link #surfaceY} 取）。
     */
    public static void ensureBuilt(ServerLevel level, BlockPos feet) {
        int cx = feet.getX();
        int cz = feet.getZ();
        int floorY = feet.getY() - 1;

        // 预载覆盖整个场地的区块，避免 setBlock 落在未加载区块上被静默丢弃
        int minChunkX = (cx - DECOR_RADIUS) >> 4;
        int maxChunkX = (cx + DECOR_RADIUS) >> 4;
        int minChunkZ = (cz - DECOR_RADIUS) >> 4;
        int maxChunkZ = (cz + DECOR_RADIUS) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }

        buildGround(level, cx, cz, floorY);
        scatterRocks(level, cx, cz, floorY);
        buildForceField(level, cx, cz, floorY);
        buildPillars(level, cx, cz, floorY);
        buildBorderLights(level, cx, cz, floorY);
        clearCenter(level, cx, cz, floorY);
    }

    // ---------------------------------------------------------------- 地面

    private static void buildGround(ServerLevel level, int cx, int cz, int floorY) {
        BlockState edge = Blocks.GILDED_BLACKSTONE.defaultBlockState();
        BlockState tileA = Blocks.DEEPSLATE_TILES.defaultBlockState();
        BlockState tileB = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        BlockState vein = Blocks.CRYING_OBSIDIAN.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        double radiusSq = (double) RADIUS * RADIUS;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > radiusSq) {
                    continue;
                }
                BlockState state;
                if (dx * dx + dz * dz > (RADIUS - 1) * (RADIUS - 1)) {
                    state = edge;
                } else if ((dx + dz) % 9 == 0 || (dx - dz) % 11 == 0) {
                    state = vein;
                } else {
                    state = (Math.floorDiv(dx, 2) + Math.floorDiv(dz, 2)) % 2 == 0 ? tileA : tileB;
                }
                BlockPos floor = new BlockPos(cx + dx, floorY, cz + dz);
                level.setBlock(floor, state, 2);
                // 削平地表起伏：地面上方 4 格清成空气
                for (int dy = 1; dy <= 4; dy++) {
                    BlockPos above = floor.above(dy);
                    if (!level.getBlockState(above).isAir()) {
                        level.setBlock(above, air, 2);
                    }
                }
            }
        }
    }

    /** 场地外缘撒一些碎石堆，让黑石荒原不至于像一块平板。 */
    private static void scatterRocks(ServerLevel level, int cx, int cz, int floorY) {
        int inner = RADIUS + 2;
        int innerSq = inner * inner;
        int decorSq = DECOR_RADIUS * DECOR_RADIUS;

        for (int dx = -DECOR_RADIUS; dx <= DECOR_RADIUS; dx += 2) {
            for (int dz = -DECOR_RADIUS; dz <= DECOR_RADIUS; dz += 2) {
                int distSq = dx * dx + dz * dz;
                if (distSq < innerSq || distSq > decorSq) {
                    continue;
                }
                int hash = hash(cx + dx, cz + dz);
                if (hash % 5 != 0) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                int groundY = surfaceY(level, x, z) - 1;
                if (groundY < floorY - 6 || groundY > floorY + 6) {
                    continue;
                }
                int height = 1 + hash % 3;
                BlockState rock = (hash % 3 == 0) ? Blocks.BLACKSTONE.defaultBlockState()
                        : Blocks.BASALT.defaultBlockState();
                for (int i = 0; i < height; i++) {
                    level.setBlock(new BlockPos(x, groundY + 1 + i, z), rock, 2);
                }
            }
        }
    }

    private static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return Math.abs(h ^ (h >>> 16));
    }

    // ---------------------------------------------------------------- 边界

    private static void buildForceField(ServerLevel level, int cx, int cz, int floorY) {
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        double radiusSq = (double) RADIUS * RADIUS;
        double innerSq = (double) (RADIUS - 1) * (RADIUS - 1);

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq > radiusSq || distSq < innerSq) {
                    continue;
                }
                for (int dy = 1; dy <= WALL_HEIGHT; dy++) {
                    level.setBlock(new BlockPos(cx + dx, floorY + dy, cz + dz), barrier, 2);
                }
            }
        }
    }

    private static void buildBorderLights(ServerLevel level, int cx, int cz, int floorY) {
        for (int angle = 0; angle < 360; angle += 30) {
            double rad = Math.toRadians(angle);
            int x = (int) Math.round(Math.cos(rad) * (RADIUS - 1));
            int z = (int) Math.round(Math.sin(rad) * (RADIUS - 1));
            BlockPos base = new BlockPos(cx + x, floorY + 1, cz + z);
            level.setBlock(base, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState(), 2);
            level.setBlock(base.above(), Blocks.SOUL_LANTERN.defaultBlockState(), 2);
        }
    }

    /**
     * 四根符文石柱，替代原来的中央祭坛——祭坛占了中心 3 格，领域之主传送进来会直接卡在方块里。
     * 现在中心完全空着，石柱挪到斜角方向。
     */
    private static void buildPillars(ServerLevel level, int cx, int cz, int floorY) {
        int[][] corners = {{7, 7}, {7, -7}, {-7, 7}, {-7, -7}};
        BlockState crying = Blocks.CRYING_OBSIDIAN.defaultBlockState();
        BlockState chiseled = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();

        for (int[] c : corners) {
            BlockPos base = new BlockPos(cx + c[0], floorY + 1, cz + c[1]);
            level.setBlock(base, chiseled, 2);
            level.setBlock(base.above(), crying, 2);
            level.setBlock(base.above(2), crying, 2);
            level.setBlock(base.above(3), Blocks.END_ROD.defaultBlockState(), 2);
        }
    }

    /** 中心 3×3×4 强制留空，保证传送进来的实体不会嵌在方块里。 */
    private static void clearCenter(ServerLevel level, int cx, int cz, int floorY) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 1; dy <= 4; dy++) {
                    BlockPos pos = new BlockPos(cx + dx, floorY + dy, cz + dz);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, air, 2);
                    }
                }
            }
        }
    }

    /** 玩家进场的环形落点（确保脚下与头顶是空气，避免传送到石柱或墙里）。 */
    public static BlockPos spawnPos(BlockPos center, int index) {
        double angle = (index % 8) * (Math.PI * 2.0D / 8.0D);
        int x = center.getX() + (int) Math.round(Math.cos(angle) * 6.0D);
        int z = center.getZ() + (int) Math.round(Math.sin(angle) * 6.0D);
        return new BlockPos(x, center.getY(), z);
    }
}
