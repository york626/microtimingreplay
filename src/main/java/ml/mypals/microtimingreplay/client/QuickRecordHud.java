package ml.mypals.microtimingreplay.client;

import ml.mypals.microtimingreplay.util.MTRComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * 录制中的角标：`● 录制中 12/5000 刻 · 34 事件`。
 *
 * <p>只画在左上角，不挡热键栏、不挡 F3；不录制时一行都不画。
 */
@Environment(EnvType.CLIENT)
public final class QuickRecordHud {

    private static final int MARGIN = 6;
    private static final int COLOR_RECORDING = 0xFFFF5555;
    private static final int COLOR_SHADOW = 0x55000000;

    private QuickRecordHud() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.options.hideGui || !QuickRecordState.isRecording()) {
                return;
            }
            Font font = minecraft.font;

            String line1 = MTRComponent.translatable("mtr.hud.recording", "● 录制中 %s/%s 刻 · %s 事件",
                    QuickRecordState.ticks(), QuickRecordState.limit(), QuickRecordState.events())
                    .getString();
            String line2 = MTRComponent.translatable("mtr.hud.profile", "配置档：%s", QuickRecordState.profile())
                    .getString();

            // 先铺一层半透明底，再写字 —— 反过来的话字会被盖住
            int boxWidth = Math.max(font.width(line1), font.width(line2)) + 4;
            int boxHeight = font.lineHeight * 2 + 4;
            graphics.fill(MARGIN - 2, MARGIN - 2, MARGIN + boxWidth, MARGIN + boxHeight, COLOR_SHADOW);
            graphics.drawString(font, line1, MARGIN, MARGIN, COLOR_RECORDING, true);
            graphics.drawString(font, line2, MARGIN, MARGIN + font.lineHeight + 2, 0xFFDDDDDD, true);
        });
    }
}
