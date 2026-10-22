package ml.mypals.microtimingreplay.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ml.mypals.microtimingreplay.MTRState;
import ml.mypals.microtimingreplay.event.ReceivedGameEventEvent;
import ml.mypals.microtimingreplay.profile.MTRProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VibrationSystem.Ticker.class)
public interface VibrationSystemTickerMixin {

    @WrapOperation(method = "receiveVibration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gameevent/vibrations/VibrationSystem$User;onReceiveVibration(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;F)V"))
    private static void mtr$onReceiveVibration(VibrationSystem.User instance, ServerLevel serverLevel, BlockPos origin, Holder<GameEvent> gameEventHolder, @Nullable Entity sourceEntity, @Nullable Entity projOwner, float v, Operation<Void> original) {
        // 正式环境里 Minecraft 的类没有局部变量表，@Local(name = "blockPos2") 必然找不到（会导致崩服）。
        // 这里改写成和原版 Ticker#receiveVibration 内同样的算法：
        // blockPos2 = callback.getPositionSource().getPosition(world).map(BlockPos::containing).orElse(origin)
        BlockPos destination = instance.getPositionSource()
            .getPosition(serverLevel)
            .map(BlockPos::containing)
            .orElse(origin);
        if (MTRState.isRecording(serverLevel)) {
            MTRProfile profile = MTRState.getActiveProfile();
            String dim = serverLevel.dimension().location().toString();
            boolean inside = profile != null && (!profile.outsideArea(destination, dim) || !profile.outsideArea(origin, dim));
            if (inside) {
                MTRState.pushEvent(new ReceivedGameEventEvent(
                    serverLevel.getServer().getTickCount() - MTRState.getRecordStartTick(),
                    destination.getX(), destination.getY(), destination.getZ(),
                    origin.getX(), origin.getY(), origin.getZ(),
                    sourceEntity != null ? sourceEntity.getStringUUID() : "",
                    projOwner != null ? projOwner.getStringUUID() : "",
                    dim
                ));
                original.call(instance, serverLevel, origin, gameEventHolder, sourceEntity, projOwner, v);
                MTRState.popEvent();
                return;
            }
        }
        original.call(instance, serverLevel, origin, gameEventHolder, sourceEntity, projOwner, v);
    }
}
