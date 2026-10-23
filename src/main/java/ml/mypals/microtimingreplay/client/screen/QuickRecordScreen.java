package ml.mypals.microtimingreplay.client.screen;

import ml.mypals.microtimingreplay.client.ClientReplayState;
import ml.mypals.microtimingreplay.client.LitematicaSelection;
import ml.mypals.microtimingreplay.client.MTRClientNetworking;
import ml.mypals.microtimingreplay.client.QuickRecordState;
import ml.mypals.microtimingreplay.util.MTRComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 「动态记录」界面 —— 给不想背指令的人用的一页。
 *
 * <p>流程就三步：先用 Litematica 框好选区 → 点「开始动态记录」→ 录完点「停止记录」。
 * 建配置档、加区域这些原本要三条指令的事全在服务端自动做了（见 {@code QuickRecordService}）。
 *
 * <p>原来的 /mtr 指令与紫色染料选区工具**没有删**，从「更多玩法」进。
 */
@Environment(EnvType.CLIENT)
public class QuickRecordScreen extends Screen {

    private static final int PANEL_WIDTH = 320;
    private static final int LINE = 12;

    private final Screen parent;
    private LitematicaSelection.Selection selection;
    private String hint = "";

    public QuickRecordScreen(Screen parent) {
        super(MTRComponent.translatable("mtr.quick.title", "动态记录"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.lastRecording = QuickRecordState.isRecording();
        this.lastReplaying = isReplaying();
        this.selection = LitematicaSelection.capture();

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = this.height / 2 - 60;
        int buttonWidth = 150;

        addRenderableWidget(Button.builder(
                        MTRComponent.translatable("mtr.quick.refresh", "重新读取选区"),
                        b -> {
                            this.selection = LitematicaSelection.capture();
                            this.hint = "";
                        })
                .bounds(left, top + 88, buttonWidth, 20).build());

        addRenderableWidget(Button.builder(
                        MTRComponent.translatable("mtr.quick.help", "更多玩法（原指令）"),
                        b -> this.minecraft.setScreen(new HelpScreen(this)))
                .bounds(left + PANEL_WIDTH - buttonWidth, top + 88, buttonWidth, 20).build());

        addRenderableWidget(Button.builder(
                        MTRComponent.translatable("mtr.quick.back", "返回"),
                        b -> this.minecraft.setScreen(this.parent))
                .bounds(left + PANEL_WIDTH / 2 - 50, top + 114, 100, 20).build());

        addRenderableWidget(Button.builder(recordButtonLabel(), b -> onRecordButton())
                .bounds(left + 8, top + 56, 150, 24).build());

        addRenderableWidget(Button.builder(replayButtonLabel(), b -> onReplayButton())
                .bounds(left + PANEL_WIDTH - 158, top + 56, 150, 24).build());
    }

    private Component recordButtonLabel() {
        return QuickRecordState.isRecording()
                ? MTRComponent.translatable("mtr.quick.stop", "■ 停止记录")
                : MTRComponent.translatable("mtr.quick.start", "● 开始动态记录");
    }

    private Component replayButtonLabel() {
        if (isReplaying()) {
            return MTRComponent.translatable("mtr.quick.replay_stop", "■ 停止回放");
        }
        return MTRComponent.translatable("mtr.quick.replay", "▶ 回放这段");
    }

    /** 当前这份配置档是否正在回放（以服务端推下来的 sessions 列表为准）。 */
    private boolean isReplaying() {
        String profile = QuickRecordState.profile();
        return !profile.isEmpty() && ClientReplayState.runningSessions().contains(profile);
    }

    private void onReplayButton() {
        String profile = QuickRecordState.profile();
        if (profile.isEmpty()) {
            this.hint = MTRComponent.translatable("mtr.quick.no_profile",
                    "还没有录过东西：先用「开始动态记录」录一段").getString();
            return;
        }
        this.hint = "";
        if (isReplaying()) {
            MTRClientNetworking.quickReplayStop(profile);
            return;
        }
        MTRClientNetworking.quickReplayStart(profile);
        // 启动成功后服务端会推 OpenScreen(TIMELINE)，这里先关界面让玩家看到世界
        this.minecraft.setScreen(null);
    }

    private void onRecordButton() {
        if (QuickRecordState.isRecording()) {
            MTRClientNetworking.quickRecordStop();
            return;
        }
        if (!LitematicaSelection.available()) {
            this.hint = MTRComponent.translatable("mtr.quick.no_litematica",
                    "没检测到 Litematica：请先装 Litematica 并框一个选区").getString();
            return;
        }
        this.selection = LitematicaSelection.capture();
        if (this.selection == null) {
            this.hint = MTRComponent.translatable("mtr.quick.no_selection",
                    "没有选区：先在 Litematica 里框一个区域").getString();
            return;
        }
        this.hint = "";
        MTRClientNetworking.quickRecordStart(this.selection.name(),
                this.selection.min(), this.selection.max());
        // 按钮文案与状态由服务端回包刷新，这里先把界面关掉，让玩家看着 HUD 录
        this.minecraft.setScreen(null);
    }

    @Override
    public void tick() {
        // 只在「录 / 没录」翻转时重建按钮，避免每刻重建导致焦点乱跳
        boolean replaying = isReplaying();
        if (this.lastRecording != QuickRecordState.isRecording() || this.lastReplaying != replaying) {
            this.lastRecording = QuickRecordState.isRecording();
            this.lastReplaying = replaying;
            this.rebuildWidgets();
        }
    }

    private boolean lastRecording;
    private boolean lastReplaying;

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x66000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = this.height / 2 - 60;

        graphics.drawCenteredString(this.font, this.title, this.width / 2, top - 20, 0xFFFFFFFF);

        // 选区信息
        if (this.selection == null) {
            graphics.drawString(this.font,
                    MTRComponent.translatable("mtr.quick.selection_none", "选区：未框选").getString(),
                    left, top, 0xFFFFAA55, false);
        } else {
            graphics.drawString(this.font,
                    MTRComponent.translatable("mtr.quick.selection", "选区：%s", this.selection.name()).getString(),
                    left, top, 0xFFFFFFFF, false);
            graphics.drawString(this.font,
                    String.format("%s ~ %s",
                            pos(this.selection.min()), pos(this.selection.max())),
                    left, top + LINE, 0xFFBBBBBB, false);
            graphics.drawString(this.font,
                    MTRComponent.translatable("mtr.quick.volume", "体积：%s 格（上限 1,000,000）",
                            this.selection.volume()).getString(),
                    left, top + LINE * 2, 0xFFBBBBBB, false);
        }

        // 录制状态
        String status = QuickRecordState.isRecording()
                ? MTRComponent.translatable("mtr.quick.status_recording", "录制中：第 %s/%s 刻 · %s 个事件",
                        QuickRecordState.ticks(), QuickRecordState.limit(), QuickRecordState.events()).getString()
                : MTRComponent.translatable("mtr.quick.status_idle", "未在录制").getString();
        graphics.drawString(this.font, status, left, top + LINE * 3 + 2,
                QuickRecordState.isRecording() ? 0xFFFF7777 : 0xFFAAAAAA, false);

        String message = this.hint.isEmpty() ? QuickRecordState.message() : this.hint;
        if (!message.isEmpty()) {
            graphics.drawString(this.font, message, left, top + 104, 0xFFFFDD55, false);
        }
    }

    private static String pos(net.minecraft.core.BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }
}
