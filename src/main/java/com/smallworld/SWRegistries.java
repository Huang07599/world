package com.smallworld;

import com.smallworld.entity.DomainLord;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 所有注册表入口：实体、物品、创造模式标签页。 */
public final class SWRegistries {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, SmallWorld.MOD_ID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(SmallWorld.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SmallWorld.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<DomainLord>> DOMAIN_LORD =
            ENTITY_TYPES.register("domain_lord", () -> EntityType.Builder
                    .of(DomainLord::new, MobCategory.MONSTER)
                    .sized(0.7F, 2.0F)
                    .eyeHeight(1.85F)
                    .clientTrackingRange(10)
                    .build("domain_lord"));

    public static final DeferredItem<Item> DOMAIN_CORE = ITEMS.register("domain_core",
            () -> new com.smallworld.item.DomainCoreItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> DOMAIN_LORD_SPAWN_EGG = ITEMS.register("domain_lord_spawn_egg",
            () -> new SpawnEggItem(DOMAIN_LORD.get(), 0x140B22, 0x35E7FF, new Item.Properties()));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.smallworld"))
                    .icon(() -> DOMAIN_CORE.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(DOMAIN_CORE.get());
                        output.accept(DOMAIN_LORD_SPAWN_EGG.get());
                    })
                    .build());

    private SWRegistries() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
    }
}
