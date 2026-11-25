package com.smallworld.item;

import com.smallworld.domain.DomainManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 领域核：玩家自己撕开一个小世界。
 *
 * <p>右键在脚下展开一个只属于自己的微型世界，30 秒后自动返回；在里面再次右键可以提前退出。</p>
 */
public class DomainCoreItem extends Item {

    private static final int DURATION_SECONDS = 30;

    public DomainCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (DomainManager.isInDomain(serverPlayer)) {
            DomainManager.leave(serverPlayer);
        } else if (DomainManager.openPlayerDomain(serverPlayer, DURATION_SECONDS)) {
            player.getCooldowns().addCooldown(this, 20 * 45);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.smallworld.domain_core.desc"));
    }
}
