package com.smallworld.domain;

import com.smallworld.SmallWorld;
import com.smallworld.entity.DomainLord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 领域会话管理器：负责「展开 → 拉人 → 计时 → 送回」的完整生命周期。
 *
 * <p>当前版本同一时刻只允许一个领域存在（一张共享的微型世界地图）。需要并发多个领域时，
 * 把 {@link #active} 换成 Map 并给每个会话分配不同的 {@link #ARENA_CENTER} 即可。</p>
 */
public final class DomainManager {

    public static final ResourceKey<Level> DOMAIN_LEVEL = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(SmallWorld.MOD_ID, "domain"));

    /** 微型世界里的竞技场中心（脚下方块顶部所在高度）。 */
    public static final BlockPos ARENA_CENTER = new BlockPos(0, 65, 0);

    private static Session active;
    private static int sessionCounter;

    private DomainManager() {
    }

    public record ReturnTicket(ResourceKey<Level> dimension, double x, double y, double z, float yRot, float xRot) {
    }

    public static final class Session {
        public final UUID ownerId;
        public final boolean playerOwned;
        public final ServerLevel originLevel;
        public final Vec3 originPos;
        public final float originYRot;
        public final float originXRot;
        public final BlockPos center;
        public final int totalTicks;

        private final Set<UUID> players = new LinkedHashSet<>();
        private final Map<UUID, ReturnTicket> tickets = new HashMap<>();
        private int ticksLeft;

        private Session(UUID ownerId, boolean playerOwned, ServerLevel originLevel, Vec3 originPos,
                        float originYRot, float originXRot, BlockPos center, int totalTicks) {
            this.ownerId = ownerId;
            this.playerOwned = playerOwned;
            this.originLevel = originLevel;
            this.originPos = originPos;
            this.originYRot = originYRot;
            this.originXRot = originXRot;
            this.center = center;
            this.totalTicks = totalTicks;
            this.ticksLeft = totalTicks;
        }

        public int ticksLeft() {
            return this.ticksLeft;
        }

        public int secondsLeft() {
            return Math.max(0, this.ticksLeft / 20);
        }

        public Set<UUID> players() {
            return this.players;
        }

        public boolean contains(UUID playerId) {
            return this.players.contains(playerId);
        }
    }

    // ------------------------------------------------------------ open / close

    /** 领域之主展开领域：把半径内的玩家和它自己拉进微型世界。 */
    public static boolean openDomain(ServerLevel level, DomainLord lord) {
        if (active != null) {
            return false;
        }
        ServerLevel domain = level.getServer().getLevel(DOMAIN_LEVEL);
        if (domain == null) {
            SmallWorld.LOGGER.error("找不到维度 smallworld:domain —— 检查 data/smallworld/dimension/domain.json");
            return false;
        }

        double r = DomainLord.DOMAIN_RADIUS;
        List<ServerPlayer> targets = new ArrayList<>(level.getPlayers(p ->
                p.isAlive() && !p.isCreative() && !p.isSpectator() && p.distanceToSqr(lord) <= r * r));
        if (targets.isEmpty()) {
            return false;
        }

        DomainArena.ensureBuilt(domain, ARENA_CENTER);

        Session session = new Session(lord.getUUID(), false, level, lord.position(),
                lord.getYRot(), lord.getXRot(), ARENA_CENTER, DomainLord.DOMAIN_DURATION);

        int index = 0;
        for (ServerPlayer player : targets) {
            session.tickets.put(player.getUUID(), ticketOf(player));
            session.players.add(player.getUUID());
            sendInto(domain, player, spawnPointFor(session.center, index++));
            player.displayClientMessage(Component.translatable("smallworld.domain.entered"), false);
        }

        // 领域之主自己也进入小世界
        Vec3 lordPos = new Vec3(session.center.getX() + 0.5D, session.center.getY(), session.center.getZ() + 0.5D);
        teleport(lord, domain, lordPos, lord.getYRot(), lord.getXRot());

        active = session;
        sessionCounter++;
        SmallWorld.LOGGER.info("领域 #{} 展开：{} 名玩家被拉入小世界", sessionCounter, session.players.size());
        return true;
    }

    /** 玩家用「领域核」自己开一个私人小世界。 */
    public static boolean openPlayerDomain(ServerPlayer player, int seconds) {
        if (active != null) {
            player.displayClientMessage(Component.translatable("smallworld.domain.busy"), true);
            return false;
        }
        ServerLevel domain = player.server.getLevel(DOMAIN_LEVEL);
        if (domain == null) {
            return false;
        }
        DomainArena.ensureBuilt(domain, ARENA_CENTER);

        Session session = new Session(player.getUUID(), true, (ServerLevel) player.level(), player.position(),
                player.getYRot(), player.getXRot(), ARENA_CENTER, seconds * 20);
        session.tickets.put(player.getUUID(), ticketOf(player));
        session.players.add(player.getUUID());
        active = session;

        sendInto(domain, player, spawnPointFor(session.center, 0));
        player.displayClientMessage(Component.translatable("smallworld.domain.entered"), false);
        return true;
    }

    /** 领域结束（时间到 / 崩塌），把玩家与其拥有者送回原处。 */
    public static void closeCurrent(boolean collapsed) {
        if (active == null) {
            return;
        }
        Session session = active;
        active = null;

        MinecraftServer server = session.originLevel.getServer();
        ServerLevel domain = server.getLevel(DOMAIN_LEVEL);

        for (UUID id : new ArrayList<>(session.players)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || !player.isAlive()) {
                continue;
            }
            ReturnTicket ticket = session.tickets.get(id);
            if (ticket != null) {
                sendBack(player, ticket);
            }
            player.displayClientMessage(Component.translatable(
                    collapsed ? "smallworld.domain.collapsed" : "smallworld.domain.ended"), false);
        }
        session.players.clear();

        // 领域之主归位
        Entity owner = findEntity(server, domain, session.ownerId);
        if (owner instanceof DomainLord lord) {
            teleport(lord, session.originLevel, session.originPos, session.originYRot, session.originXRot);
            lord.onDomainClosed();
        }

        if (domain != null) {
            domain.playSound(null, session.center, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 1.2F, 1.4F);
        }
        SmallWorld.LOGGER.info("领域关闭（collapsed={}）", collapsed);
    }

    /** 拥有者实体（领域之主）死亡或被移除时调用。 */
    public static void closeForOwner(UUID ownerId, boolean collapsed) {
        if (active != null && active.ownerId.equals(ownerId)) {
            closeCurrent(collapsed);
        }
    }

    public static boolean isOwnerInDomain(UUID ownerId) {
        return active != null && !active.playerOwned && active.ownerId.equals(ownerId);
    }

    public static boolean isInDomain(ServerPlayer player) {
        return active != null && active.contains(player.getUUID());
    }

    public static Session activeSession() {
        return active;
    }

    // ------------------------------------------------------------ ticking

    public static void tick(MinecraftServer server) {
        if (active == null) {
            return;
        }
        Session session = active;
        ServerLevel domain = server.getLevel(DOMAIN_LEVEL);
        session.ticksLeft--;

        if (domain == null) {
            closeCurrent(true);
            return;
        }

        Iterator<UUID> it = session.players.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || !player.isAlive() || player.level() != domain) {
                it.remove();
                session.tickets.remove(id);
                continue;
            }
            // 掉出世界兜底
            if (player.getY() < ARENA_CENTER.getY() - 40.0D) {
                sendInto(domain, player, spawnPointFor(session.center, 0));
            }
            if (session.ticksLeft % 20 == 0) {
                player.displayClientMessage(Component.translatable(
                        "smallworld.domain.time_left", session.secondsLeft()), true);
            }
        }

        if (session.players.isEmpty()) {
            closeCurrent(false);
            return;
        }
        if (session.ticksLeft <= 0) {
            closeCurrent(false);
        }
    }

    public static void onLogout(ServerPlayer player) {
        if (active == null || !active.contains(player.getUUID())) {
            return;
        }
        active.players.remove(player.getUUID());
        active.tickets.remove(player.getUUID());
        if (active.players.isEmpty()) {
            closeCurrent(false);
        }
    }

    /** 主动离开领域。 */
    public static boolean leave(ServerPlayer player) {
        if (active == null || !active.contains(player.getUUID())) {
            return false;
        }
        ReturnTicket ticket = active.tickets.remove(player.getUUID());
        active.players.remove(player.getUUID());
        if (ticket != null) {
            sendBack(player, ticket);
        }
        player.displayClientMessage(Component.translatable("smallworld.domain.left"), false);
        if (active != null && active.players.isEmpty()) {
            closeCurrent(false);
        }
        return true;
    }

    // ------------------------------------------------------------ helpers

    private static ReturnTicket ticketOf(ServerPlayer player) {
        return new ReturnTicket(player.level().dimension(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());
    }

    private static Vec3 spawnPointFor(BlockPos center, int index) {
        double angle = (index % 8) * (Math.PI * 2.0D / 8.0D);
        return new Vec3(center.getX() + 0.5D + Math.cos(angle) * 6.0D,
                center.getY(),
                center.getZ() + 0.5D + Math.sin(angle) * 6.0D);
    }

    private static void sendInto(ServerLevel domain, ServerPlayer player, Vec3 pos) {
        teleport(player, domain, pos, player.getYRot(), player.getXRot());
        domain.playSound(null, player.blockPosition(), SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.8F, 0.6F);
    }

    private static void sendBack(ServerPlayer player, ReturnTicket ticket) {
        ServerLevel dest = player.server.getLevel(ticket.dimension());
        if (dest == null) {
            dest = player.server.overworld();
        }
        teleport(player, dest, new Vec3(ticket.x(), ticket.y(), ticket.z()), ticket.yRot(), ticket.xRot());
        player.displayClientMessage(Component.translatable("smallworld.domain.returned"), true);
    }

    private static Entity findEntity(MinecraftServer server, ServerLevel domain, UUID id) {
        if (domain != null) {
            Entity entity = domain.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    /**
     * 跨维度传送的统一入口。
     * 1.21.1 里玩家用 {@code teleportTo(ServerLevel, x, y, z, Set<Relative>, yRot, xRot)}，
     * 其它实体用 {@code changeDimension(ServerLevel)}——后者只把我们送到目标维度的
     * 默认落点，所以还要手动摆到竞技场坐标上。
     */
    public static void teleport(Entity entity, ServerLevel target, Vec3 pos, float yRot, float xRot) {
        if (entity.level() == target) {
            entity.teleportTo(pos.x, pos.y, pos.z);
            entity.setYRot(yRot);
            entity.setXRot(xRot);
        } else if (entity instanceof ServerPlayer player) {
            player.teleportTo(target, pos.x, pos.y, pos.z, Set.of(), yRot, xRot);
        } else {
            Entity moved = entity.changeDimension(target);
            Entity result = moved != null ? moved : entity;
            result.setPos(pos.x, pos.y, pos.z);
            result.setYRot(yRot);
            result.setXRot(xRot);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        entity.resetFallDistance();
    }
}
