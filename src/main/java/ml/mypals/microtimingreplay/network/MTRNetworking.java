package ml.mypals.microtimingreplay.network;

import ml.mypals.microtimingreplay.config.RecordingEventRegistry;
import ml.mypals.microtimingreplay.config.RecordingFilterConfig;
import ml.mypals.microtimingreplay.MTRState;
import ml.mypals.microtimingreplay.profile.MTRProfile;
import ml.mypals.microtimingreplay.profile.ProfileManager;
import ml.mypals.microtimingreplay.record.QuickRecordService;
import ml.mypals.microtimingreplay.replay.PlayerPositioner;
import ml.mypals.microtimingreplay.replay.ReplayContext;
import ml.mypals.microtimingreplay.replay.ReplayManager;
import ml.mypals.microtimingreplay.replay.ReplaySession;
import ml.mypals.microtimingreplay.replay.stackTrace.StackTraceManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server half of the client add-on protocol: payload registration, the receive
 * handlers, and the push helpers the replay engine calls when state moves.
 *
 * <p>Every handler that changes something re-checks operator level 2.
 * The command tree gates on it too, but packets do not go through the command tree —
 * without this any client could drive somebody else's replay.
 */
public class MTRNetworking {

    /** Keeps one scroll gesture from asking the server for arbitrarily much work. */
    private static final int MAX_STEP_AMOUNT = 4096;

    /**
     * Runs on both sides — the mod's main entrypoint is environment-agnostic, and both
     * ends must agree on the type table before any handler is registered.
     */
    public static void registerTypes() {
        PayloadTypeRegistry.playC2S().register(MTRPayloads.HelloC2S.TYPE, MTRPayloads.HelloC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.SubscribeC2S.TYPE, MTRPayloads.SubscribeC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.RequestTimelineC2S.TYPE, MTRPayloads.RequestTimelineC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.RequestDetailsC2S.TYPE, MTRPayloads.RequestDetailsC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.StepC2S.TYPE, MTRPayloads.StepC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.JumpC2S.TYPE, MTRPayloads.JumpC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.SetCameraFollowC2S.TYPE, MTRPayloads.SetCameraFollowC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.RequestFilterC2S.TYPE, MTRPayloads.RequestFilterC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.SetFilterC2S.TYPE, MTRPayloads.SetFilterC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.ResetFilterC2S.TYPE, MTRPayloads.ResetFilterC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.QuickRecordC2S.TYPE, MTRPayloads.QuickRecordC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.HighlightC2S.TYPE, MTRPayloads.HighlightC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.QuickReplayC2S.TYPE, MTRPayloads.QuickReplayC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.SetScoreboardC2S.TYPE, MTRPayloads.SetScoreboardC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.ListProfilesC2S.TYPE, MTRPayloads.ListProfilesC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.DeleteProfileC2S.TYPE, MTRPayloads.DeleteProfileC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.ClearMarkersC2S.TYPE, MTRPayloads.ClearMarkersC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MTRPayloads.SetReplayPrefsC2S.TYPE, MTRPayloads.SetReplayPrefsC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(MTRPayloads.ProfileListS2C.TYPE, MTRPayloads.ProfileListS2C.CODEC);

        PayloadTypeRegistry.playS2C().register(MTRPayloads.HelloS2C.TYPE, MTRPayloads.HelloS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MTRPayloads.SessionsS2C.TYPE, MTRPayloads.SessionsS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MTRPayloads.CursorS2C.TYPE, MTRPayloads.CursorS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MTRPayloads.FilterS2C.TYPE, MTRPayloads.FilterS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MTRPayloads.OpenScreenS2C.TYPE, MTRPayloads.OpenScreenS2C.CODEC);
        // These two can outgrow the 1 MiB vanilla payload cap. Fabric's networking API on
        // 1.21.1 has no registerLarge, so a timeline past that ceiling drops the connection
        // rather than being split — keep an eye on it for very long recordings.
        PayloadTypeRegistry.playS2C().register(MTRPayloads.TimelineS2C.TYPE, MTRPayloads.TimelineS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MTRPayloads.DetailsS2C.TYPE, MTRPayloads.DetailsS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MTRPayloads.QuickRecordStateS2C.TYPE, MTRPayloads.QuickRecordStateS2C.CODEC);
    }

    public static void registerServerHandlers() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // Restore before they go: game mode is saved to player data, so a player who
            // logs out mid-follow would otherwise come back stuck in spectator.
            PlayerPositioner.onDisconnect(handler.getPlayer());
            ml.mypals.microtimingreplay.replay.scoreboard.ScoreboardPrefs.forget(handler.getPlayer());
            MTRClientTracker.forget(handler.getPlayer());
        });

        // 回放选择页：要一份可回放的录制列表（按文件改动时间倒序，最多 40 条）
        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.ListProfilesC2S.TYPE, (payload, context) ->
                sendProfileList(context.player()));

        // 回放期偏好：跳过掉落物实体
        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.SetReplayPrefsC2S.TYPE, (payload, context) -> {
            if (!isAllowed(context.player())) return;
            ml.mypals.microtimingreplay.replay.ReplayPrefs.setSkipItemEntities(payload.skipItemEntities());
            ml.mypals.microtimingreplay.replay.ReplayPrefs.setSkipPlayerStandIns(payload.skipPlayerStandIns());
            ml.mypals.microtimingreplay.replay.ReplayPrefs.setMarkersLeafOnly(payload.markersLeafOnly());
            ml.mypals.microtimingreplay.replay.ReplayPrefs.setCleanRecording(payload.cleanRecording());
            ml.mypals.microtimingreplay.replay.ReplayPrefs.setShowBlockMarkers(payload.showBlockMarkers());
        });

        // 一键清理世界里残留的标记展示体（所有维度、不分会话）
        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.ClearMarkersC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAllowed(player)) return;
            for (net.minecraft.server.level.ServerLevel level : player.server.getAllLevels()) {
                ml.mypals.microtimingreplay.marker.PistonDisplayManager.clearAll(level);
                ml.mypals.microtimingreplay.replay.EntityReplayManager.clearAll(level);
                ml.mypals.microtimingreplay.marker.MarkerManager.clearEveryMarker(level);
            }
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("已清理世界里的标记"), true);
        });

        // 删除一份录制（正在放的话先停掉，否则文件被占）
        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.DeleteProfileC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAllowed(player)) return;
            String name = payload.name();
            if (ReplayManager.isRunning(name)) {
                ReplayManager.stop(name);
            }
            ProfileManager.deleteProfile(name);
            sendProfileList(player);
        });

        // 时间轴侧栏 显示/隐藏
        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.SetScoreboardC2S.TYPE, (payload, context) ->
                ml.mypals.microtimingreplay.replay.scoreboard.ScoreboardPrefs.set(context.player(), !payload.visible()));

        // 时间轴点行 → 方块高亮（不推进回放）
        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.HighlightC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAllowed(player)) return;
            ReplaySession session = ReplayManager.subscribedSession(player);
            if (session == null || !session.isRunning()) return;
            session.highlightStep(player.serverLevel(), payload.step());
        });

        // GUI「回放 / 停止回放」：等价 /mtr replay start|stop
        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.QuickReplayC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAllowed(player)) return;

            String name = payload.profile();
            if (payload.action() == MTRPayloads.QuickReplayC2S.ACTION_STOP) {
                ReplayManager.stop(name);
                sendSessions(player);
                return;
            }

            MTRProfile profile = ProfileManager.loadProfile(name);
            if (profile == null) {
                ServerPlayNetworking.send(player, new MTRPayloads.QuickRecordStateS2C(false, name, 0, 0,
                        5000, "找不到配置档：" + name));
                return;
            }
            if (!MTRState.canStartReplay() || ReplayManager.isRunning(name)) {
                ServerPlayNetworking.send(player, new MTRPayloads.QuickRecordStateS2C(false, name, 0, 0, 5000,
                        ReplayManager.isRunning(name) ? "这个配置档已经在回放中：" + name : "正在录制中，先停止录制"));
                return;
            }
            // 他想边逐步边看，所以默认先把时间冻上（等价 /tick freeze），省一步
            net.minecraft.server.ServerTickRateManager tickRate = player.server.tickRateManager();
            if (!tickRate.isFrozen()) {
                tickRate.setFrozen(true);
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("已自动冻结时间（/tick unfreeze 恢复）"), true);
            }
            // 准备阶段要读档 + 备份区域世界，大区域会顿一下；先告诉玩家不是卡死
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("正在准备回放（备份区域世界）…"), true);
            long mtrStartAt = System.nanoTime();
            ReplaySession started = ReplayManager.start(profile);
            long mtrStartMs = (System.nanoTime() - mtrStartAt) / 1_000_000L;
            if (mtrStartMs >= 200) {
                ml.mypals.microtimingreplay.MicroTimingReplay.LOGGER.info(
                        "[mtr-perf] replay-start {}ms profile={}", mtrStartMs, name);
            }
            if (started == null) {
                ServerPlayNetworking.send(player, new MTRPayloads.QuickRecordStateS2C(false, name, 0, 0, 5000,
                        "回放启动失败：" + name));
                return;
            }
            ReplayManager.subscribe(player, name);
            sendSessions(player);
            ServerPlayNetworking.send(player, new MTRPayloads.OpenScreenS2C(MTRPayloads.OpenScreenS2C.TIMELINE));
        });

        // 「动态记录」：客户端只送选区与名字，建配置档 / 加区域 / 开录全在服务端做
        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.QuickRecordC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAllowed(player)) return;

            QuickRecordService.State state = payload.action() == MTRPayloads.QuickRecordC2S.ACTION_STOP
                    ? QuickRecordService.stop(player)
                    : QuickRecordService.start(player, payload.name(), payload.min(), payload.max());

            ServerPlayNetworking.send(player, new MTRPayloads.QuickRecordStateS2C(
                    state.recording(), state.profile(), state.ticks(), state.events(),
                    state.limit(), state.message()));
        });

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.HelloC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MTRClientTracker.onHello(player, payload.protocol());
            ServerPlayNetworking.send(player, new MTRPayloads.HelloS2C(MTRPayloads.PROTOCOL_VERSION, isAllowed(player)));
            sendSessions(player);
            QuickRecordService.pushTo(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.SubscribeC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAllowed(player)) return;

            if (payload.profile().isEmpty()) {
                ReplayManager.unsubscribe(player);
            } else {
                ReplayManager.subscribe(player, payload.profile());
            }
            sendSessions(player);
            sendTimeline(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.RequestTimelineC2S.TYPE,
                (payload, context) -> sendTimeline(context.player()));

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.RequestDetailsC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ReplaySession session = ReplayManager.subscribedSession(player);
            if (session == null) return;
            sendDetails(player, session, payload.step());
        });

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.StepC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAllowed(player)) return;
            if (ReplaySession.isUnitInvalid(payload.unit())) return;

            ReplaySession session = ReplayManager.subscribedSession(player);
            if (session == null || !session.isRunning()) return;

            session.advance(player.serverLevel(), Math.clamp(payload.amount(), 1, MAX_STEP_AMOUNT),
                    payload.unit(), payload.forward());
        });

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.JumpC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAllowed(player)) return;

            ReplaySession session = ReplayManager.subscribedSession(player);
            if (session == null || !session.isRunning()) return;

            session.jumpToStep(player.serverLevel(), payload.step());
        });

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.SetCameraFollowC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAllowed(player)) return;

            if (!payload.enabled()) {
                PlayerPositioner.disable(player);
            } else {
                ReplaySession session = ReplayManager.subscribedSession(player);
                // Following nothing would just strand them in spectator.
                if (session == null || !session.isRunning()) return;

                PlayerPositioner.enable(player);
                PlayerPositioner.focusOnCursor(player, session, player.serverLevel());
            }
            sendSessions(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.RequestFilterC2S.TYPE,
                (payload, context) -> sendFilter(context.player()));

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.SetFilterC2S.TYPE, (payload, context) -> {
            if (!isAllowed(context.player())) return;
            if (RecordingEventRegistry.get(payload.eventId()) == null) return;

            RecordingFilterConfig.setMode(payload.eventId(), payload.mode());
            broadcastFilter(context.server());
        });

        ServerPlayNetworking.registerGlobalReceiver(MTRPayloads.ResetFilterC2S.TYPE, (payload, context) -> {
            if (!isAllowed(context.player())) return;

            RecordingFilterConfig.resetToDefaults();
            broadcastFilter(context.server());
        });
    }

    public static boolean isAllowed(ServerPlayer player) {
        return player != null && player.hasPermissions(2);
    }

    // ── pushes ───────────────────────────────────────────────────────────────

    public static void sendSessions(ServerPlayer player) {
        if (!MTRClientTracker.hasClient(player)) return;

        ReplaySession subscribed = ReplayManager.subscribedSession(player);
        ServerPlayNetworking.send(player, new MTRPayloads.SessionsS2C(
                ReplayManager.runningProfileNames(),
                subscribed == null ? "" : subscribed.sessionId(),
                PlayerPositioner.isFollowing(player)));
    }

    public static void broadcastSessions(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSessions(player);
        }
    }

    private static void sendProfileList(ServerPlayer player) {
        if (player == null || !isAllowed(player)) return;

        List<String> names = new ArrayList<>(ProfileManager.listProfiles());
        names.sort((a, b) -> Long.compare(
                ml.mypals.microtimingreplay.profile.WorldScopedStorage.getProfileFile(b).lastModified(),
                ml.mypals.microtimingreplay.profile.WorldScopedStorage.getProfileFile(a).lastModified()));

        List<MTRPayloads.ProfileEntry> entries = new ArrayList<>();
        int limit = Math.min(names.size(), 40);
        for (int i = 0; i < limit; i++) {
            String name = names.get(i);
            MTRProfile profile = ProfileManager.loadProfile(name);
            if (profile == null) continue;
            int events = 0;
            for (ml.mypals.microtimingreplay.profile.TickFrame frame : profile.getFrames()) {
                events += frame.getEvents().size();
            }
            entries.add(new MTRPayloads.ProfileEntry(name, profile.getTicksRecorded(), events));
        }
        ServerPlayNetworking.send(player, new MTRPayloads.ProfileListS2C(entries));
    }

    public static void sendTimeline(ServerPlayer player) {
        if (!MTRClientTracker.hasClient(player)) return;

        ReplaySession session = ReplayManager.subscribedSession(player);
        if (session == null || !session.isRunning()) {
            ServerPlayNetworking.send(player, new MTRPayloads.TimelineS2C("", -1, List.of()));
            return;
        }

        // 行表按会话缓存（见 ReplaySession#timelineRows），游标走 O(1) 的前缀表
        ServerPlayNetworking.send(player,
                new MTRPayloads.TimelineS2C(session.sessionId(),
                        session.rowCursor().cursorRow(), session.timelineRows()));
    }

    public static void broadcastCursor(ReplaySession session, List<ServerPlayer> subscribers) {
        if (session == null || subscribers.isEmpty()) return;

        MTRPayloads.CursorS2C payload = null;
        for (ServerPlayer player : subscribers) {
            if (!MTRClientTracker.hasClient(player)) continue;
            if (payload == null) {
                // Built lazily: most sessions have no add-on clients watching, and this
                // runs on every single step.
                ReplaySession.RowCursor cursor = session.rowCursor();
                payload = new MTRPayloads.CursorS2C(
                        session.sessionId(),
                        cursor.cursorRow(),
                        session.getCurrentVirtualTick(),
                        session.getTotalTick(),
                        cursor.totalRows());
            }
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static void sendDetails(ServerPlayer player, ReplaySession session, int step) {
        if (!MTRClientTracker.hasClient(player)) return;

        Component hover = Component.empty();
        Optional<BlockPos> focus = Optional.empty();
        for (ReplaySession.ReplayAction action : session.getFlatActionsSnapshot()) {
            if (action.type() != ReplaySession.ActionType.EXIT && action.visibleStepIndex() == step) {
                hover = action.event().fillHoverText();
                // Null for events with no place in the world; the client then keeps its old focus.
                focus = Optional.ofNullable(action.event().getMarkerPos());
                break;
            }
        }

        // Traces live in a per-session map keyed off the replay context.
        List<String> trace = ReplayContext.call(session.sessionId(), () -> StackTraceManager.get(step));
        ServerPlayNetworking.send(player,
                new MTRPayloads.DetailsS2C(step, hover, trace == null ? List.of() : trace, focus));
    }

    /**
     * Serves {@code /mtr replay screen} for an add-on client: ship the data, then ask
     * for the screen. Returns false when the player has no add-on, so the caller falls
     * back to the dialog.
     */
    public static boolean openTimelineScreen(ServerPlayer player) {
        if (!MTRClientTracker.hasClient(player)) return false;

        sendTimeline(player);
        ServerPlayNetworking.send(player, new MTRPayloads.OpenScreenS2C(MTRPayloads.OpenScreenS2C.TIMELINE));
        return true;
    }

    public static boolean openFilterScreen(ServerPlayer player) {
        if (!MTRClientTracker.hasClient(player)) return false;

        sendFilter(player);
        ServerPlayNetworking.send(player, new MTRPayloads.OpenScreenS2C(MTRPayloads.OpenScreenS2C.FILTER));
        return true;
    }

    public static void sendFilter(ServerPlayer player) {
        if (!MTRClientTracker.hasClient(player)) return;

        RecordingFilterConfig.ensureLoaded();
        List<MTRPayloads.FilterRow> rows = new ArrayList<>();
        for (RecordingEventRegistry.EventEntry entry : RecordingEventRegistry.getAll()) {
            rows.add(new MTRPayloads.FilterRow(entry.id(), entry.category(), entry.defaultName(),
                    RecordingFilterConfig.mode(entry.id()), entry.isOption()));
        }
        ServerPlayNetworking.send(player, new MTRPayloads.FilterS2C(rows));
    }

    /** The filter is world-wide state, so everyone looking at it should see the change. */
    private static void broadcastFilter(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendFilter(player);
        }
    }
}
