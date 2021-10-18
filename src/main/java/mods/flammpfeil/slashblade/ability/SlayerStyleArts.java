package mods.flammpfeil.slashblade.ability;

import com.google.common.collect.Sets;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.capability.mobeffect.CapabilityMobEffect;
import mods.flammpfeil.slashblade.capability.slashblade.ComboState;
import mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword;
import mods.flammpfeil.slashblade.event.InputCommandEvent;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.util.InputCommand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class SlayerStyleArts {
    private static final class SingletonHolder {
        private static final SlayerStyleArts instance = new SlayerStyleArts();
    }

    public static SlayerStyleArts getInstance() {
        return SlayerStyleArts.SingletonHolder.instance;
    }

    private SlayerStyleArts() {
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    final static EnumSet<InputCommand> fowerd_sprint_sneak = EnumSet.of(InputCommand.FORWARD, InputCommand.SPRINT, InputCommand.SNEAK);
    final static EnumSet<InputCommand> move = EnumSet.of(InputCommand.FORWARD, InputCommand.BACK, InputCommand.LEFT, InputCommand.RIGHT);

    @SubscribeEvent
    public void onInputChange(InputCommandEvent event) {

        EnumSet<InputCommand> old = event.getOld();
        EnumSet<InputCommand> current = event.getCurrent();
        ServerPlayer sender = event.getPlayer();
        Level worldIn = sender.level;

        if(!old.contains(InputCommand.SPRINT)){

            boolean isHandled = false;

            if(current.containsAll(fowerd_sprint_sneak)){
                //air trick
                isHandled = sender.getMainHandItem().getCapability(ItemSlashBlade.BLADESTATE).map(state->{
                    Entity tmpTarget = state.getTargetEntity(worldIn);

                    Entity target;

                    if(tmpTarget.getParts() != null && 0 < tmpTarget.getParts().length){
                        target = tmpTarget.getParts()[0];
                    }else{
                        target = tmpTarget;
                    }

                    if(target == null) return false;

                    if(target == sender.getLastHurtMob() && sender.tickCount < sender.getLastHurtMobTimestamp() + 100){
                        LivingEntity hitEntity = sender.getLastHurtMob();
                        if(hitEntity != null){
                            SlayerStyleArts.doTeleport(sender, hitEntity);
                        }
                    }else{
                        EntityAbstractSummonedSword ss = new EntityAbstractSummonedSword(SlashBlade.RegistryEvents.SummonedSword, worldIn){
                            @Override
                            protected void onHitEntity(EntityHitResult p_213868_1_) {
                                super.onHitEntity(p_213868_1_);

                                LivingEntity target = sender.getLastHurtMob();
                                if(target != null && this.getHitEntity() == target){
                                    SlayerStyleArts.doTeleport(sender, target);
                                }
                            }

                            @Override
                            public void tick() {
                                if(this.getPersistentData().getBoolean("doForceHit")) {
                                    this.doForceHitEntity(target);
                                    this.getPersistentData().remove("doForceHit");
                                }
                                super.tick();
                            }
                        };

                        Vec3 lastPos = sender.getEyePosition(1.0f);
                        ss.xOld = lastPos.x;
                        ss.yOld = lastPos.y;
                        ss.zOld = lastPos.z;

                        Vec3 targetPos = target.position().add(0, target.getBbHeight() / 2.0, 0).add(sender.getLookAngle().scale(-2.0));
                        ss.setPos(targetPos.x, targetPos.y, targetPos.z);

                        Vec3 dir = sender.getLookAngle();
                        ss.shoot(dir.x, dir.y, dir.z, 1.0f, 0);

                        ss.setOwner(sender);

                        ss.setDamage(0.01f);

                        ss.setColor(state.getColorCode());

                        ss.getPersistentData().putBoolean("doForceHit",true);

                        worldIn.addFreshEntity(ss);
                        sender.playNotifySound(SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 0.2F, 1.45F);

                        //ss.doForceHitEntity(target);
                    }

                    return true;
                }).orElse(false);
            }

            if(!isHandled && sender.isOnGround() && current.contains(InputCommand.SPRINT) && current.stream().anyMatch(cc->move.contains(cc))){
                //quick avoid ground

                int count = sender.getCapability(CapabilityMobEffect.MOB_EFFECT)
                        .map(ef->ef.doAvoid(sender.level.getGameTime()))
                        .orElse(0);

                if(0 < count){
                    Untouchable.setUntouchable(sender, 10);

                    float moveForward = current.contains(InputCommand.FORWARD) == current.contains(InputCommand.BACK) ? 0.0F : (current.contains(InputCommand.FORWARD) ? 1.0F : -1.0F);
                    float moveStrafe = current.contains(InputCommand.LEFT) == current.contains(InputCommand.RIGHT) ? 0.0F : (current.contains(InputCommand.LEFT) ? 1.0F : -1.0F);
                    Vec3 input = new Vec3(moveStrafe,0,moveForward);

                    sender.moveRelative(3.0f, input);

                    Vec3 motion = this.maybeBackOffFromEdge(sender.getDeltaMovement(), sender);

                    sender.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.5f, 1.2f);

                    sender.move(MoverType.SELF, motion);

                    sender.moveTo(sender.position());

                    sender.getMainHandItem().getCapability(ItemSlashBlade.BLADESTATE)
                            .ifPresent(state->state.updateComboSeq(sender, state.getComboRootAir()));
                }

                isHandled = true;
            }
            //slow avoid ground
            //move double tap

            /**
             //relativeList : pos -> convertflag -> motion
             sender.connection.setPlayerLocation(sender.getPosX(), sender.getPosY(), sender.getPosZ()
             , sender.getYaw(1.0f), sender.getPitch(1.0f)
             , Sets.newHashSet(SPlayerPositionLookPacket.Flags.X,SPlayerPositionLookPacket.Flags.Z));
             */
        }

    }

    private static void doTeleport(Entity entityIn, LivingEntity target) {
        if(!(entityIn.level instanceof ServerLevel)) return;

        if(entityIn instanceof Player) {
            Player player = ((Player) entityIn);
            player.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.75F, 1.25F);

            player.getMainHandItem().getCapability(ItemSlashBlade.BLADESTATE)
                    .ifPresent(state -> state.updateComboSeq(player, state.getComboRootAir()));

            Untouchable.setUntouchable(player, 10);
        }

        ServerLevel worldIn = (ServerLevel) entityIn.level;

        Vec3 tereportPos = target.position().add(0,target.getBbHeight() / 2.0, 0).add(entityIn.getLookAngle().scale(-2.0));

        double x = tereportPos.x;
        double y = tereportPos.y;
        double z = tereportPos.z;
        float yaw = entityIn.getYRot();
        float pitch = entityIn.getXRot();

        Set<ClientboundPlayerPositionPacket.RelativeArgument> relativeList = Collections.emptySet();
        BlockPos blockpos = new BlockPos(x, y, z);
        if (!Level.isInSpawnableBounds(blockpos)) {
            return;
        } else {
            if (entityIn instanceof ServerPlayer) {
                ChunkPos chunkpos = new ChunkPos(new BlockPos(x, y, z));
                worldIn.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 1, entityIn.getId());
                entityIn.stopRiding();
                if (((ServerPlayer)entityIn).isSleeping()) {
                    ((ServerPlayer)entityIn).stopSleepInBed(true, true);
                }

                if (worldIn == entityIn.level) {
                    ((ServerPlayer)entityIn).connection.teleport(x, y, z, yaw, pitch, relativeList);
                } else {
                    ((ServerPlayer)entityIn).teleportTo(worldIn, x, y, z, yaw, pitch);
                }

                entityIn.setYHeadRot(yaw);
            } else {
                float f1 = Mth.wrapDegrees(yaw);
                float f = Mth.wrapDegrees(pitch);
                f = Mth.clamp(f, -90.0F, 90.0F);
                if (worldIn == entityIn.level) {
                    entityIn.moveTo(x, y, z, f1, f);
                    entityIn.setYHeadRot(f1);
                } else {
                    entityIn.unRide();
                    Entity entity = entityIn;
                    entityIn = entityIn.getType().create(worldIn);
                    if (entityIn == null) {
                        return;
                    }

                    entityIn.restoreFrom(entity);
                    entityIn.moveTo(x, y, z, f1, f);
                    entityIn.setYHeadRot(f1);
                    //worldIn.addFromAnotherDimension(entityIn);
                }
            }

            if (!(entityIn instanceof LivingEntity) || !((LivingEntity)entityIn).isFallFlying()) {
                entityIn.setDeltaMovement(entityIn.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
                entityIn.setOnGround(false);
            }

            if (entityIn instanceof PathfinderMob) {
                ((PathfinderMob)entityIn).getNavigation().stop();
            }

        }
    }

    protected Vec3 maybeBackOffFromEdge(Vec3 vec, LivingEntity mover) {
        double d0 = vec.x;
        double d1 = vec.z;
        double d2 = 0.05D;

        while(d0 != 0.0D && mover.level.noCollision(mover, mover.getBoundingBox().move(d0, (double)(-mover.maxUpStep), 0.0D))) {
            if (d0 < 0.05D && d0 >= -0.05D) {
                d0 = 0.0D;
            } else if (d0 > 0.0D) {
                d0 -= 0.05D;
            } else {
                d0 += 0.05D;
            }
        }

        while(d1 != 0.0D && mover.level.noCollision(mover, mover.getBoundingBox().move(0.0D, (double)(-mover.maxUpStep), d1))) {
            if (d1 < 0.05D && d1 >= -0.05D) {
                d1 = 0.0D;
            } else if (d1 > 0.0D) {
                d1 -= 0.05D;
            } else {
                d1 += 0.05D;
            }
        }

        while(d0 != 0.0D && d1 != 0.0D && mover.level.noCollision(mover, mover.getBoundingBox().move(d0, (double)(-mover.maxUpStep), d1))) {
            if (d0 < 0.05D && d0 >= -0.05D) {
                d0 = 0.0D;
            } else if (d0 > 0.0D) {
                d0 -= 0.05D;
            } else {
                d0 += 0.05D;
            }

            if (d1 < 0.05D && d1 >= -0.05D) {
                d1 = 0.0D;
            } else if (d1 > 0.0D) {
                d1 -= 0.05D;
            } else {
                d1 += 0.05D;
            }
        }

        vec = new Vec3(d0, vec.y, d1);

        return vec;
    }
}