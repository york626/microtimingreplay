package ml.mypals.microtimingreplay.client.screen;

import ml.mypals.microtimingreplay.client.ClientProfileList;
import ml.mypals.microtimingreplay.client.ClientReplayState;
import ml.mypals.microtimingreplay.client.MTRClientNetworking;
import ml.mypals.microtimingreplay.network.MTRPayloads;
import ml.mypals.microtimingreplay.util.MTRComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 回放选择页：把录过的每一段列出来，**点哪一段就回放哪一段**。
 *
 * <p>列表来自服务端（{@code ListProfilesC2S} → {@code ProfileListS2C}），按文件改动时间倒序。
 * 点一条 = 发 {@code QuickReplayC2S(START)}，服务端启动后会自动把时间轴界面推过来。
 */
@Environment(EnvType.CLIENT)
public class ReplayPickerScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int LIST_MARGIN = 40;
    private static final int LIST_TOP = 40;

    private final Screen parent;
    private int scroll;
    private long lastRevision = Long.MIN_VALUE;

    public ReplayPickerScreen(Screen parent) {
        super(MTRComponent.translatable("mtr.picker.title", "回放"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        MTRClientNetworking.requestProfiles();
        rebuildRows();
    }

    private void rebuildRows() {
        this.clearWidgets();
        List<MTRPayloads.ProfileEntry> entries = ClientProfileList.entries();
        int width = Math.min(360, this.width - LIST_MARGIN * 2);
        int x = (this.width - width) / 2;

        int visible = visibleRows();
        for (int i = 0; i < entries.size() && i < scroll + visible; i++) {
            if (i < scroll) continue;
            MTRPayloads.ProfileEntry entry = entries.get(i);
            int y = LIST_TOP + (i - scroll) * ROW_HEIGHT;
            boolean running = ClientReplayState.runningSessions().contains(entry.name());
            addRenderableWidget(Button.builder(
                            running
                                    ? MTRComponent.translatable("mtr.picker.playing", "正在回放 · 点此停止")
                                    : MTRComponent.translatable("mtr.picker.play", "▶ 回放"),
                            b -> onPick(entry, running))
                    .bounds(x + width - 96, y, 96, 18).build());
        }

        addRenderableWidget(Button.builder(MTRComponent.translatable("mtr.picker.close", "关闭"),
                        b -> this.minecraft.setScreen(this.parent))
                .bounds(this.width / 2 - 50, this.height - 30, 100, 20).build());
    }

    private void onPick(MTRPayloads.ProfileEntry entry, boolean running) {
        if (running) {
            MTRClientNetworking.quickReplayStop(entry.name());
            return;
        }
        MTRClientNetworking.quickReplayStart(entry.name());
        // 服务端启动后会推 OpenScreen(TIMELINE)，这里先关掉列表
        this.minecraft.setScreen(null);
    }

    private int visibleRows() {
        return Math.max(1, (this.height - LIST_TOP - 46) / ROW_HEIGHT);
    }

    @Override
    public void tick() {
        if (ClientProfileList.revision() != lastRevision) {
            lastRevision = ClientProfileList.revision();
            rebuildRows();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, ClientProfileList.entries().size() - visibleRows());
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        scroll = Math.clamp(scroll - (int) Math.round(scrollY), 0, maxScroll);
        rebuildRows();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);

        List<MTRPayloads.ProfileEntry> entries = ClientProfileList.entries();
        int width = Math.min(360, this.width - LIST_MARGIN * 2);
        int x = (this.width - width) / 2;

        if (entries.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    MTRComponent.translatable("mtr.picker.empty", "还没有录制：先用「动态记录」录一段").getString(),
                    this.width / 2, this.height / 2, 0xFFFFAA55);
            return;
        }

        int visible = visibleRows();
        int last = Math.min(entries.size(), scroll + visible);
        for (int i = scroll; i < last; i++) {
            MTRPayloads.ProfileEntry entry = entries.get(i);
            int y = LIST_TOP + (i - scroll) * ROW_HEIGHT;
            graphics.fill(x, y, x + width, y + ROW_HEIGHT - 2, 0x33000000);

            String name = entry.name();
            if (this.font.width(name) > width - 190) {
                while (name.length() > 4 && this.font.width(name + "…") > width - 190) {
                    name = name.substring(0, name.length() - 1);
                }
                name = name + "…";
            }
            graphics.drawString(this.font, name, x + 6, y + 4, 0xFFFFFFFF, false);

            String detail = MTRComponent.translatable("mtr.picker.detail", "%s 刻 · %s 事件",
                    entry.ticks(), entry.events()).getString();
            graphics.drawString(this.font, detail, x + width - 100 - this.font.width(detail) - 6, y + 4,
                    0xFFAAAAAA, false);
        }

        if (entries.size() > visible) {
            graphics.drawString(this.font,
                    MTRComponent.translatable("mtr.picker.scroll", "滚轮翻页  %s/%s",
                            scroll + 1, Math.max(1, entries.size() - visible + 1)).getString(),
                    x, this.height - 44, 0xFF888888, false);
        }
    }
}
