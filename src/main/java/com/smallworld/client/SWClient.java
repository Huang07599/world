package com.smallworld.client;

import com.smallworld.SmallWorld;
import com.smallworld.SWRegistries;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端：给领域之主挂一个类人模型（复用人形模型骨骼，只换贴图，省掉一个自定义模型）。
 */
@EventBusSubscriber(modid = SmallWorld.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SWClient {

    private SWClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SWRegistries.DOMAIN_LORD.get(), Renderer::new);
    }

    private static final class Renderer
            extends HumanoidMobRenderer<com.smallworld.entity.DomainLord, HumanoidModel<com.smallworld.entity.DomainLord>> {

        private static final ResourceLocation TEXTURE =
                ResourceLocation.fromNamespaceAndPath(SmallWorld.MOD_ID, "textures/entity/domain_lord.png");

        Renderer(EntityRendererProvider.Context context) {
            super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.6F);
        }

        @Override
        public ResourceLocation getTextureLocation(com.smallworld.entity.DomainLord entity) {
            return TEXTURE;
        }
    }
}
