package ml.mypals.microtimingreplay.mixin;

import ml.mypals.microtimingreplay.MicroTimingReplay;
import ml.mypals.microtimingreplay.replay.ReplayManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诊断（临时）：回放期间任何物品实体被生成时，把物品、坐标和**完整调用栈**打进日志。
 * 用来定位「活塞伸出/收回时凭空掉出活塞物品」的真实生成路径——静态排查已排除
 * 录制数据与快照实体，直接让掉落代码自己报名字。
 */
@Mixin(ServerLevel.class)
public abstract class ItemSpawnDiagnosticMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void mtr$logItemSpawnDuringReplay(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!ReplayManager.isAnyRunning()) return;
        if (!(entity instanceof ItemEntity item)) return;
        MicroTimingReplay.LOGGER.info("[mtr-itemdiag] 回放期间生成物品 {} @ ({}, {}, {})",
                item.getItem(),
                entity.getX(), entity.getY(), entity.getZ(),
                new Throwable("[mtr-itemdiag] 生成调用栈").fillInStackTrace());
    }
}
