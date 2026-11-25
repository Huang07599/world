package com.smallworld;

import com.smallworld.command.SWCommand;
import com.smallworld.domain.DomainManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Set;

/**
 * 所有挂在「游戏总线」（{@code NeoForge.EVENT_BUS}）上的事件。
 *
 * <p>注意：注册期的 mod bus 事件（如 {@code EntityAttributeCreationEvent}）**不能**放在这里，
 * 见 {@link SWModEvents}。</p>
 */
public final class SWEvents {

    private SWEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        DomainManager.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        SWCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DomainManager.onLogout(player);
        }
    }

    /** 在领域里死亡后，复活点会落到微型维度里，这里把人送回主世界。 */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.level().dimension().equals(DomainManager.DOMAIN_LEVEL)) {
            return;
        }
        ServerLevel overworld = player.server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY() + 1.0D, spawn.getZ() + 0.5D,
                Set.of(), player.getYRot(), player.getXRot());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DomainManager.closeAll(false);
    }
}
