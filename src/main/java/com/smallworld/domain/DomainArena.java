package com.smallworld.domain;

import com.smallworld.entity.DomainLord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 在微型维度里就地「长」出一座漂浮的竞技场。
 *
 * <p>微型维度本身是空世界（见 data/smallworld/dimension/domain.json，只有最底层一层基岩），
 * 所以这里的搭建结果就是玩家进入领域后看到的全部「小世界」：黑石圆台、隐形力场墙、灵魂火把边界、
 * 中央祭坛。搭建是幂等的，重复调用只会覆盖成同样的方块，因此每次展开都直接重建，无需持久化记录。</p>
 */
public final class DomainArena {

    /** 圆台半径（比领域判定半径略大，避免玩家站在边缘被判定到外面）。 */
    public static final int RADIUS = DomainLord.DOMAIN_RADIUS + 5;
    private static final int WALL_HEIGHT = 8;

    private DomainArena() {
    }

    public static void ensureBuilt(ServerLevel level, BlockPos center) {
        // 预载覆盖整个圆台的区块，避免 setBlock 落在未加载区块上被静默丢弃
        int minChunkX = (center.getX() - RADIUS) >> 4;
        int maxChunkX = (center.getX() + RADIUS) >> 4;
        int minChunkZ = (center.getZ() - RADIUS) >> 4;
        int maxChunkZ = (center.getZ() + RADIUS) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                level.getChunk(cx, cz);
            }
        }

        int floorY = center.getY() - 1;
        BlockState edge = Blocks.GILDED_BLACKSTONE.defaultBlockState();
        BlockState tileA = Blocks.DEEPSLATE_TILES.defaultBlockState();
        BlockState tileB = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        BlockState vein = Blocks.CRYING_OBSIDIAN.defaultBlockState();

        // 地面
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > RADIUS) {
                    continue;
                }
                BlockState state;
                if (dist > RADIUS - 1.2D) {
                    state = edge;
                } else if ((dx + dz) % 9 == 0 || (dx - dz) % 11 == 0) {
                    state = vein;
                } else {
                    state = (Math.floorDiv(dx, 2) + Math.floorDiv(dz, 2)) % 2 == 0 ? tileA : tileB;
                }
                level.setBlockAndUpdate(new BlockPos(center.getX() + dx, floorY, center.getZ() + dz), state);
            }
        }

        // 隐形力场墙：拦住玩家，视觉上只有边界灯柱
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        for (int y = floorY + 1; y <= floorY + WALL_HEIGHT; y++) {
            for (int a = 0; a < 360; a += 2) {
                double rad = Math.toRadians(a);
                int x = (int) Math.round(Math.cos(rad) * RADIUS);
                int z = (int) Math.round(Math.sin(rad) * RADIUS);
                level.setBlockAndUpdate(new BlockPos(center.getX() + x, y, center.getZ() + z), barrier);
            }
        }

        // 边界灯柱 + 灵魂灯笼
        for (int a = 0; a < 360; a += 30) {
            double rad = Math.toRadians(a);
            int x = (int) Math.round(Math.cos(rad) * (RADIUS - 1));
            int z = (int) Math.round(Math.sin(rad) * (RADIUS - 1));
            BlockPos base = new BlockPos(center.getX() + x, floorY + 1, center.getZ() + z);
            level.setBlockAndUpdate(base, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
            level.setBlockAndUpdate(base.above(), Blocks.SOUL_LANTERN.defaultBlockState());
        }

        // 中央祭坛
        BlockPos core = new BlockPos(center.getX(), floorY + 1, center.getZ());
        for (int y = 0; y < 3; y++) {
            level.setBlockAndUpdate(core.above(y), Blocks.CRYING_OBSIDIAN.defaultBlockState());
        }
        level.setBlockAndUpdate(core.above(3), Blocks.END_ROD.defaultBlockState());
    }
}
