package com.smallworld;

import com.smallworld.entity.DomainLord;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

/**
 * 只放「mod bus」事件（即实现了 {@code IModBusEvent} 的那些，比如注册期的各类 Register/Attribute 事件）。
 *
 * <p>这些事件必须挂在 {@code modEventBus} 上。一旦把它们和普通游戏事件混在同一个类里、
 * 又用 {@code NeoForge.EVENT_BUS.register(类)} 整类注册，NeoForge 就会在启动时报
 * {@code IModBusEvent events are not allowed on the common NeoForge bus} 并拒绝加载模组。
 * 所以这里刻意与 {@link SWEvents}（游戏总线）分开。</p>
 */
public final class SWModEvents {

    private SWModEvents() {
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(SWRegistries.DOMAIN_LORD.get(), DomainLord.createAttributes().build());
    }
}
