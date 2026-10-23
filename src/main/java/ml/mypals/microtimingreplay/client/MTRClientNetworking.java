package ml.mypals.microtimingreplay.client;

import ml.mypals.microtimingreplay.config.RecordMode;
import ml.mypals.microtimingreplay.client.screen.FilterScreen;
import ml.mypals.microtimingreplay.client.screen.TimelineScreen;
import ml.mypals.microtimingreplay.network.MTRPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;


@Environment(EnvType.CLIENT)
public class MTRClientNetworking {

    public static void registerClientHandlers() {
        ClientPlayNetworking.registerGlobalReceiver(MTRPayloads.HelloS2C.TYPE, (payload, context) ->
                ClientReplayState.onHello(payload.protocol() == MTRPayloads.PROTOCOL_VERSION, payload.allowed()));

        ClientPlayNetworking.registerGlobalReceiver(MTRPayloads.SessionsS2C.TYPE, (payload, context) ->
                ClientReplayState.onSessions(payload.running(), payload.subscribed(), payload.cameraFollow()));

        ClientPlayNetworking.registerGlobalReceiver(MTRPayloads.TimelineS2C.TYPE, (payload, context) ->
                ClientReplayState.onTimeline(payload));

        ClientPlayNetworking.registerGlobalReceiver(MTRPayloads.CursorS2C.TYPE, (payload, context) ->
                ClientReplayState.onCursor(payload));

        ClientPlayNetworking.registerGlobalReceiver(MTRPayloads.DetailsS2C.TYPE, (payload, context) ->
                ClientReplayState.onDetails(payload));

        ClientPlayNetworking.registerGlobalReceiver(MTRPayloads.FilterS2C.TYPE, (payload, context) ->
                ClientReplayState.onFilter(payload.rows()));

        ClientPlayNetworking.registerGlobalReceiver(MTRPayloads.ProfileListS2C.TYPE, (payload, context) ->
                ClientProfileList.set(payload.entries()));

        ClientPlayNetworking.registerGlobalReceiver(MTRPayloads.QuickRecordStateS2C.TYPE, (payload, context) ->
                QuickRecordState.update(payload.recording(), payload.profile(), payload.ticks(),
                        payload.events(), payload.limit(), payload.message()));

        ClientPlayNetworking.registerGlobalReceiver(MTRPayloads.OpenScreenS2C.TYPE, (payload, context) -> {
            Screen screen = switch (payload.screen()) {
                case MTRPayloads.OpenScreenS2C.TIMELINE -> new TimelineScreen();
                case MTRPayloads.OpenScreenS2C.FILTER -> new FilterScreen();
                default -> null;
            };
            if (screen != null) {
                context.client().setScreen(screen);
            }
        });
    }

    /**
     * Guarded so a vanilla server — which never registered our channels — does not get
     * an exception thrown at it every time the player scrolls.
     */
    private static void send(CustomPacketPayload payload) {
        if (ClientPlayNetworking.canSend(payload.type())) {
            ClientPlayNetworking.send(payload);
        }
    }

    public static void sendHello() {
        send(new MTRPayloads.HelloC2S(MTRPayloads.PROTOCOL_VERSION));
    }

    /** 「动态记录」开始：把 Litematica 选区与名字交给服务端。 */
    public static void quickRecordStart(String name, BlockPos min, BlockPos max) {
        send(new MTRPayloads.QuickRecordC2S(MTRPayloads.QuickRecordC2S.ACTION_START,
                name == null ? "" : name, min, max));
    }

    /** 「动态记录」停止（并保存）。 */
    public static void quickRecordStop() {
        send(new MTRPayloads.QuickRecordC2S(MTRPayloads.QuickRecordC2S.ACTION_STOP,
                "", BlockPos.ZERO, BlockPos.ZERO));
    }

    /** 时间轴点某行 → 让服务端把那个方块高亮一下。 */
    public static void highlight(int step) {
        send(new MTRPayloads.HighlightC2S(step));
    }

    /** GUI「回放」：等价 /mtr replay start <名字>。 */
    public static void quickReplayStart(String profile) {
        send(new MTRPayloads.QuickReplayC2S(MTRPayloads.QuickReplayC2S.ACTION_START, profile));
    }

    /** 清理世界里的标记展示体。 */
    public static void clearMarkers() {
        send(MTRPayloads.ClearMarkersC2S.INSTANCE);
    }

    /** 删掉某一份录制。 */
    public static void deleteProfile(String name) {
        send(new MTRPayloads.DeleteProfileC2S(name));
    }

    /** 要一份可回放的录制列表。 */
    public static void requestProfiles() {
        send(MTRPayloads.ListProfilesC2S.INSTANCE);
    }

    /** 告诉服务端要不要显示时间轴侧栏（记分板）。 */
    public static void sendScoreboardPref(boolean visible) {
        send(new MTRPayloads.SetScoreboardC2S(visible));
    }

    /** GUI「停止回放」。 */
    public static void quickReplayStop(String profile) {
        send(new MTRPayloads.QuickReplayC2S(MTRPayloads.QuickReplayC2S.ACTION_STOP, profile));
    }

    public static void requestTimeline() {
        send(MTRPayloads.RequestTimelineC2S.INSTANCE);
    }

    public static void requestFilter() {
        send(MTRPayloads.RequestFilterC2S.INSTANCE);
    }

    public static void requestDetails(int step) {
        send(new MTRPayloads.RequestDetailsC2S(step));
    }

    public static void step(boolean forward) {
        step(MTRClientConfig.stepUnit(), MTRClientConfig.stepAmount(), forward);
    }

    public static void step(String unit, int amount, boolean forward) {
        send(new MTRPayloads.StepC2S(unit, amount, forward));
    }

    public static void jump(int step) {
        send(new MTRPayloads.JumpC2S(step));
    }

    public static void subscribe(String profile) {
        send(new MTRPayloads.SubscribeC2S(profile));
    }

    public static void unsubscribe() {
        send(new MTRPayloads.SubscribeC2S(""));
    }

    public static void setFilter(String eventId, RecordMode mode) {
        send(new MTRPayloads.SetFilterC2S(eventId, mode));
    }

    public static void resetFilter() {
        send(MTRPayloads.ResetFilterC2S.INSTANCE);
    }

    public static void setCameraFollow(boolean enabled) {
        send(new MTRPayloads.SetCameraFollowC2S(enabled));
        ClientReplayState.setCameraFollow(enabled);
    }
}
