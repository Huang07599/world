package com.smallworld;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 小世界领域 / Small World Domain
 *
 * <p>核心玩法：领域之主（DomainLord）会在玩家靠近时展开「领域」——先蓄力压缩一圈粒子环，
 * 然后把范围内的玩家连同自己一起拉进实体的私有微型维度，在那边搭好的小世界里继续战斗。
 * 领域时间到、玩家全部离开、或领域之主被击杀时，所有人被送回原处。</p>
 */
@Mod(SmallWorld.MOD_ID)
public class SmallWorld {

    public static final String MOD_ID = "smallworld";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SmallWorld(IEventBus modEventBus) {
        SWRegistries.register(modEventBus);
        modEventBus.addListener(SWEvents::onEntityAttributeCreation);
        NeoForge.EVENT_BUS.register(SWEvents.class);
        LOGGER.info("[SmallWorld] 小世界领域 loaded, domain dimension = {}", "smallworld:domain");
    }
}
