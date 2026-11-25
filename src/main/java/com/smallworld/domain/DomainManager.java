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
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 领域会话管理器：负责「展开 → 拉人 → 计时 → 送回」的完整生命周期。
 *
 * <p>这一版支持<b>多个领域并存</b>：每个领域占微型维度里一块 {@link #SLOT_SPACING} 见方的地盘
 * （0 号在原点，1 号在 x=512，依此类推），互不重叠。会话按「拥有者 UUID」索引：
 * 领域之主的会话用它的实体 UUID，玩家用领域核开的私人小世界用玩家 UUID。</p>
 */
public final class DomainManager {

    public static final ResourceKey<Level> DOMAIN_LEVEL = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(SmallWorld.MOD_ID, "domain"));

    /** 每个领域的占地边长，够放下半径 21 的场地还有大片荒地。 */
    private static final int SLOT_SPACING = 512;
    private static final int SLOTS_PER_ROW = 8;
    private static final int MAX_SLOTS = 64;

    private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();
    private static int nextSlotHint;

    private DomainManager() {
    }

    public record ReturnTicket(ResourceKey<Level> dimension, double x, double y, double z, float yRot, float xRot) {
    }

    public static final class Session {
        public final UUID ownerId;
        public final boolean playerOwned;
        public final int slot;
        public final ServerLevel originLevel;
        public final Vec3 originPos;
        public final float originYRot;
        public final float originXRot;
        /** 场地中心（脚踩那一格）。 */
        public final BlockPos center;
        public final int totalTicks;

        private final Set<UUID> players = new LinkedHashSet<>();
        private final Map<UUID, ReturnTicket> tickets = new HashMap<>();
        private int ticksLeft;

        private Session(UUID ownerId, boolean playerOwned, int slot, ServerLevel originLevel, Vec3 originPos,
                        float originYRot, float originXRot, BlockPos center, int totalTicks) {
            this.ownerId = ownerId;
            this.playerOwned = playerOwned;
            this.slot = slot;
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

    // ------------------------------------------------------------ 开 / 关

    /** 领域之主展开领域：把半径内的玩家和它自己拉进微型世界。 */
    public static boolean openDomain(ServerLevel level, DomainLord lord) {
        ServerLevel domain = level.getServer().getLevel(DOMAIN_LEVEL);
        if (domain == null) {
            SmallWorld.LOGGER.error("找不到维度 smallworld:domain —— 检查 data/smallworld/dimension/domain.json");
            return false;
        }
        if (SESSIONS.containsKey(lord.getUUID())) {
            return false;
        }
        int slot = allocateSlot();
        if (slot < 0) {
            SmallWorld.LOGGER.warn("领域数量已达上限（{}），无法再展开", MAX_SLOTS);
            return false;
        }

        double radius = DomainLord.DOMAIN_RADIUS;
        List<ServerPlayer> targets = new ArrayList<>(level.getPlayers(player ->
                player.isAlive() && !player.isCreative() && !player.isSpectator()
                        && player.distanceToSqr(lord) <= radius * radius));
        if (targets.isEmpty()) {
            return false;
        }

        BlockPos center = centerFor(domain, slot);
        DomainArena.ensureBuilt(domain, center);

        Session session = new Session(lord.getUUID(), false, slot, level, lord.position(),
                lord.getYRot(), lord.getXRot(), center, DomainLord.DOMAIN_DURATION);

        // 主世界一侧开门（就在领域之主脚下），玩家跟着它一起进去
        DomainPortal.open(level, lord.position());

        int index = 0;
        for (ServerPlayer player : targets) {
            session.tickets.put(player.getUUID(), ticketOf(player));
            session.players.add(player.getUUID());
            sendInto(domain, player, spawnPointFor(session.center, index++));
            player.displayClientMessage(Component.translatable("smallworld.domain.entered"), false);
        }

        // 领域之主落在场地正中（那里刻意留了空），门开在侧边，免得它站在门里被原版传送逻辑带走
        Vec3 lordPos = Vec3.atBottomCenterOf(session.center);
        DomainPortal.open(domain, lordPos.add(4.0D, 0.0D, 0.0D));
        teleport(lord, domain, lordPos, lord.getYRot(), lord.getXRot());

        SESSIONS.put(session.ownerId, session);
        SmallWorld.LOGGER.info("领域展开（槽位 {}）：{} 名玩家被拉入小世界，当前领域数 {}",
                slot, session.players.size(), SESSIONS.size());
        return true;
    }

    /** 玩家用「领域核」自己开一个私人小世界。 */
    public static boolean openPlayerDomain(ServerPlayer player, int seconds) {
        if (isInDomain(player)) {
            player.displayClientMessage(Component.translatable("smallworld.domain.busy"), true);
            return false;
        }
        ServerLevel domain = player.server.getLevel(DOMAIN_LEVEL);
        if (domain == null) {
            return false;
        }
        int slot = allocateSlot();
        if (slot < 0) {
            player.displayClientMessage(Component.translatable("smallworld.domain.busy"), true);
            return false;
        }

        BlockPos center = centerFor(domain, slot);
        DomainArena.ensureBuilt(domain, center);

        Session session = new Session(player.getUUID(), true, slot, (ServerLevel) player.level(), player.position(),
                player.getYRot(), player.getXRot(), center, seconds * 20);
        session.tickets.put(player.getUUID(), ticketOf(player));
        session.players.add(player.getUUID());
        SESSIONS.put(session.ownerId, session);

        DomainPortal.open((ServerLevel) player.level(), player.position());
        sendInto(domain, player, spawnPointFor(session.center, 0));
        player.displayClientMessage(Component.translatable("smallworld.domain.entered"), false);
        return true;
    }

    /** 关闭某个领域的会话，把玩家与拥有者送回原处。 */
    public static void close(Session session, boolean collapsed) {
        if (session == null || SESSIONS.remove(session.ownerId) == null) {
            return;
        }
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
        session.tickets.clear();

        // 领域之主归位，进出各开一扇门
        Entity owner = findEntity(server, domain, session.ownerId);
        if (owner instanceof DomainLord lord) {
            if (domain != null) {
                DomainPortal.open(domain, lord.position());
            }
            DomainPortal.open(session.originLevel, session.originPos);
            teleport(lord, session.originLevel, session.originPos, session.originYRot, session.originXRot);
            lord.onDomainClosed();
        }

        if (domain != null) {
            domain.playSound(null, session.center, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 1.2F, 1.4F);
        }
        SmallWorld.LOGGER.info("领域关闭（collapsed={}），剩余领域 {}", collapsed, SESSIONS.size());
    }

    /** 拥有者实体（领域之主）死亡或被移除时调用。 */
    public static void closeForOwner(UUID ownerId, boolean collapsed) {
        close(SESSIONS.get(ownerId), collapsed);
    }

    /** 关掉所有领域（停服时兜底）。 */
    public static void closeAll(boolean collapsed) {
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            close(session, collapsed);
        }
        DomainPortal.clearAll();
    }

    // ------------------------------------------------------------ 查询

    public static boolean isOwnerInDomain(UUID ownerId) {
        return SESSIONS.containsKey(ownerId);
    }

    public static boolean isInDomain(ServerPlayer player) {
        return sessionOf(player) != null;
    }

    public static Session sessionOf(ServerPlayer player) {
        for (Session session : SESSIONS.values()) {
            if (session.contains(player.getUUID())) {
                return session;
            }
        }
        return null;
    }

    /** 命令用：返回任意一个会话（没有则 null）。 */
    public static Session activeSession() {
        Iterator<Session> it = SESSIONS.values().iterator();
        return it.hasNext() ? it.next() : null;
    }

    public static int sessionCount() {
        return SESSIONS.size();
    }

    public static List<Session> sessions() {
        return new ArrayList<>(SESSIONS.values());
    }

    // ------------------------------------------------------------ 每 tick

    public static void tick(MinecraftServer server) {
        DomainPortal.tick(server);
        if (SESSIONS.isEmpty()) {
            return;
        }
        ServerLevel domain = server.getLevel(DOMAIN_LEVEL);
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            tickSession(server, domain, session);
        }
    }

    private static void tickSession(MinecraftServer server, ServerLevel domain, Session session) {
        if (domain == null) {
            close(session, true);
            return;
        }
        session.ticksLeft--;

        Iterator<UUID> it = session.players.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || !player.isAlive() || player.level() != domain) {
                it.remove();
                session.tickets.remove(id);
                continue;
            }
            if (player.getY() < session.center.getY() - 40.0D) {
                sendInto(domain, player, spawnPointFor(session.center, 0));
            }
            if (session.ticksLeft % 20 == 0) {
                player.displayClientMessage(Component.translatable(
                        "smallworld.domain.time_left", session.secondsLeft()), true);
            }
        }

        if (session.players.isEmpty() || session.ticksLeft <= 0) {
            close(session, false);
        }
    }

    public static void onLogout(ServerPlayer player) {
        Session session = sessionOf(player);
        if (session == null) {
            return;
        }
        session.players.remove(player.getUUID());
        session.tickets.remove(player.getUUID());
        if (session.players.isEmpty()) {
            close(session, false);
        }
    }

    /** 主动离开领域。 */
    public static boolean leave(ServerPlayer player) {
        Session session = sessionOf(player);
        if (session == null) {
            return false;
        }
        ReturnTicket ticket = session.tickets.remove(player.getUUID());
        session.players.remove(player.getUUID());
        if (ticket != null) {
            sendBack(player, ticket);
        }
        player.displayClientMessage(Component.translatable("smallworld.domain.left"), false);
        if (session.players.isEmpty()) {
            close(session, false);
        }
        return true;
    }

    // ------------------------------------------------------------ 内部工具

    private static int allocateSlot() {
        Set<Integer> used = new HashSet<>();
        for (Session session : SESSIONS.values()) {
            used.add(session.slot);
        }
        for (int i = 0; i < MAX_SLOTS; i++) {
            int slot = (nextSlotHint + i) % MAX_SLOTS;
            if (!used.contains(slot)) {
                nextSlotHint = (slot + 1) % MAX_SLOTS;
                return slot;
            }
        }
        return -1;
    }

    /** 槽位 → 场地中心。Y 取该处实际地表，换地形也不用改这里。 */
    private static BlockPos centerFor(ServerLevel domain, int slot) {
        int x = (slot % SLOTS_PER_ROW) * SLOT_SPACING;
        int z = (slot / SLOTS_PER_ROW) * SLOT_SPACING;
        return new BlockPos(x, DomainArena.surfaceY(domain, x, z), z);
    }

    private static ReturnTicket ticketOf(ServerPlayer player) {
        return new ReturnTicket(player.level().dimension(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());
    }

    private static Vec3 spawnPointFor(BlockPos center, int index) {
        return Vec3.atBottomCenterOf(DomainArena.spawnPos(center, index));
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
     * 其它实体走 {@code changeDimension(DimensionTransition)}——注意这个类在 1.21.2 才改名叫
     * TeleportTransition，1.21.1 必须用 DimensionTransition。
     */
    public static void teleport(Entity entity, ServerLevel target, Vec3 pos, float yRot, float xRot) {
        if (entity.level() == target) {
            entity.teleportTo(pos.x, pos.y, pos.z);
            entity.setYRot(yRot);
            entity.setXRot(xRot);
        } else if (entity instanceof ServerPlayer player) {
            player.teleportTo(target, pos.x, pos.y, pos.z, Set.of(), yRot, xRot);
        } else {
            Entity moved = entity.changeDimension(new DimensionTransition(target, pos, Vec3.ZERO,
                    yRot, xRot, DimensionTransition.DO_NOTHING));
            Entity result = moved != null ? moved : entity;
            result.setPos(pos.x, pos.y, pos.z);
            result.setYRot(yRot);
            result.setXRot(xRot);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        entity.resetFallDistance();
    }
}
