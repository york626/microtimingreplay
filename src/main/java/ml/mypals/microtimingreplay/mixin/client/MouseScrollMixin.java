package ml.mypals.microtimingreplay.mixin.client;

import ml.mypals.microtimingreplay.client.ClientReplayState;
import ml.mypals.microtimingreplay.client.MTRClientConfig;
import ml.mypals.microtimingreplay.client.MTRClientNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「按住 Ctrl + 滚轮 = 前进/后退一小步」（Ray 2026-10-23 要的手感）。
 *
 * <p>只在「没有开任何界面 + 自己正在看回放」时接管，并且**取消掉原版处理**，
 * 否则滚轮会顺手把快捷栏切了。开关在 设置 → Ctrl + 滚轮 步进。
 */
@Mixin(MouseHandler.class)
public abstract class MouseScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void mtr$ctrlScrollStep(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (!MTRClientConfig.ctrlScrollStep()) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) return;
        if (!ClientReplayState.isWatching()) return;
        if (yOffset == 0) return;

        boolean ctrl = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        if (!ctrl) return;

        // 往下滚 = 前进（配合「滚轮步进」的直觉），反转滚轮方向那只开关也一并尊重
        boolean forward = yOffset < 0;
        if (MTRClientConfig.invertScroll()) forward = !forward;
        MTRClientNetworking.step(forward);
        ci.cancel();
    }
}
