package com.smallworld.command;

import com.mojang.brigadier.CommandDispatcher;
import com.smallworld.SWRegistries;
import com.smallworld.domain.DomainArena;
import com.smallworld.domain.DomainManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;

/**
 * /smallworld summon|enter|exit|rebuild —— 调试与手动触发用。
 */
public final class SWCommand {

    private SWCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("smallworld")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("summon")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ServerLevel level = (ServerLevel) player.level();
                            BlockPos pos = player.blockPosition().offset(0, 0, 2);
                            Entity entity = SWRegistries.DOMAIN_LORD.get().spawn(level, pos, MobSpawnType.COMMAND);
                            if (entity == null) {
                                ctx.getSource().sendFailure(Component.translatable("smallworld.command.summon_failed"));
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.translatable("smallworld.command.summoned"), true);
                            return 1;
                        }))
                .then(Commands.literal("enter")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (DomainManager.isInDomain(player)) {
                                ctx.getSource().sendFailure(Component.translatable("smallworld.command.already_inside"));
                                return 0;
                            }
                            boolean ok = DomainManager.openPlayerDomain(player, 60);
                            return ok ? 1 : 0;
                        }))
                .then(Commands.literal("exit")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            boolean ok = DomainManager.leave(player);
                            if (!ok) {
                                ctx.getSource().sendFailure(Component.translatable("smallworld.command.not_inside"));
                            }
                            return ok ? 1 : 0;
                        }))
                .then(Commands.literal("rebuild")
                        .executes(ctx -> {
                            ServerLevel domain = ctx.getSource().getServer().getLevel(DomainManager.DOMAIN_LEVEL);
                            if (domain == null) {
                                ctx.getSource().sendFailure(Component.literal("dimension smallworld:domain not found"));
                                return 0;
                            }
                            java.util.List<DomainManager.Session> sessions = DomainManager.sessions();
                            if (sessions.isEmpty()) {
                                DomainArena.ensureBuilt(domain,
                                        new BlockPos(0, DomainArena.surfaceY(domain, 0, 0), 0));
                            } else {
                                for (DomainManager.Session session : sessions) {
                                    DomainArena.ensureBuilt(domain, session.center);
                                }
                            }
                            ctx.getSource().sendSuccess(() -> Component.translatable("smallworld.command.rebuilt"), true);
                            return 1;
                        }))
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            int count = DomainManager.sessionCount();
                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                    "smallworld.command.status_all", count), false);
                            for (DomainManager.Session session : DomainManager.sessions()) {
                                ctx.getSource().sendSuccess(() -> Component.translatable(
                                        "smallworld.command.status", session.players().size(),
                                        session.secondsLeft()), false);
                            }
                            return count;
                        })));
    }
}
