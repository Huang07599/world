package com.smallworld.entity;

import com.smallworld.SWRegistries;
import com.smallworld.domain.DomainManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 领域之主：拥有一个「小世界」的实体。
 *
 * <p>行为循环：待机冷却 → 发现 14 格内的玩家 → 蓄力 {@link #CHARGE_TICKS} tick（收缩粒子环、信标音）
 * → 展开领域（把范围内玩家与自身拉进私有维度）→ 领域结束/自己被击杀后回到原位，重新计算冷却。</p>
 */
public class DomainLord extends Monster {

    /** 领域展开半径（格）。 */
    public static final int DOMAIN_RADIUS = 16;
    /** 蓄力时长。 */
    public static final int CHARGE_TICKS = 50;
    /** 领域持续时间（40 秒）。 */
    public static final int DOMAIN_DURATION = 20 * 40;
    /** 领域冷却（30 秒）。 */
    public static final int DOMAIN_COOLDOWN = 20 * 30;

    private final ServerBossEvent bossBar;

    private int chargeTicks;
    private int cooldown = 20 * 8;
    private boolean domainOpen;

    public DomainLord(EntityType<? extends DomainLord> type, Level level) {
        super(type, level);
        this.xpReward = 50;
        this.bossBar = new ServerBossEvent(this.getDisplayName(),
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);
        this.bossBar.setDarkenScreen(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 80.0D)
                .add(Attributes.ATTACK_DAMAGE, 6.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.29D)
                .add(Attributes.FOLLOW_RANGE, 40.0D)
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.85D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15D, false));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 24.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ---------------------------------------------------------------- ticking

    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }

        // 自己在领域里时不重复展开
        if (DomainManager.isOwnerInDomain(this.getUUID())) {
            this.chargeTicks = 0;
            this.cooldown = DOMAIN_COOLDOWN;
            this.bossBar.setProgress(1.0F);
            return;
        }

        if (this.chargeTicks > 0) {
            this.chargeTicks--;
            this.getNavigation().stop();
            this.setDeltaMovement(0.0D, Math.min(0.0D, this.getDeltaMovement().y), 0.0D);
            if (this.chargeTicks % 2 == 0) {
                this.emitChargeParticles(level);
            }
            if (this.chargeTicks == 0) {
                this.expandDomain(level);
            }
            return;
        }

        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }

        if (this.tickCount % 10 == 0) {
            Player target = this.level().getNearestPlayer(this, 14.0D);
            if (target != null && target.isAlive() && !target.isCreative() && !target.isSpectator()) {
                this.startCharge(level);
            }
        }
    }

    private void startCharge(ServerLevel level) {
        this.chargeTicks = CHARGE_TICKS;
        this.setGlowingTag(true);
        level.playSound(null, this.blockPosition(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.0F, 0.75F);
        level.sendParticles(ParticleTypes.FLASH, this.getX(), this.getY() + 1.6D, this.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private void emitChargeParticles(ServerLevel level) {
        double progress = (CHARGE_TICKS - this.chargeTicks) / (double) CHARGE_TICKS;
        double ring = DOMAIN_RADIUS * (1.0D - progress) + 1.5D;
        double spin = this.tickCount * 0.06D;
        for (int i = 0; i < 28; i++) {
            double angle = (i / 28.0D) * Math.PI * 2.0D + spin;
            double x = this.getX() + Math.cos(angle) * ring;
            double z = this.getZ() + Math.sin(angle) * ring;
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, this.getY() + 0.15D, z, 1, 0.0D, 0.02D, 0.0D, 0.0D);
            if (i % 2 == 0) {
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, this.getY() + 0.1D, z, 1, 0.0D, 0.01D, 0.0D, 0.0D);
            }
        }
        if (this.chargeTicks == CHARGE_TICKS - 1 || this.chargeTicks == CHARGE_TICKS / 2) {
            level.playSound(null, this.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.3F, 0.6F);
        }
    }

    private void expandDomain(ServerLevel level) {
        this.setGlowingTag(false);
        boolean opened = DomainManager.openDomain(level, this);
        this.cooldown = opened ? DOMAIN_COOLDOWN : 20 * 5;
        if (opened) {
            this.domainOpen = true;
            level.playSound(null, this.blockPosition(), SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 1.6F, 0.5F);
        }
    }

    /** 供 DomainManager 在领域关闭后回调。 */
    public void onDomainClosed() {
        this.domainOpen = false;
        this.cooldown = DOMAIN_COOLDOWN;
    }

    public boolean isDomainOpen() {
        return this.domainOpen;
    }

    // ---------------------------------------------------------------- misc

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossBar.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossBar.removePlayer(player);
    }

    @Override
    public void setCustomName(Component name) {
        super.setCustomName(name);
        this.bossBar.setName(this.getDisplayName());
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide) {
            DomainManager.closeForOwner(this.getUUID(), true);
        }
        super.die(source);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide) {
            DomainManager.closeForOwner(this.getUUID(), false);
        }
        super.remove(reason);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        this.spawnAtLocation(new ItemStack(SWRegistries.DOMAIN_CORE.get()));
    }
}
