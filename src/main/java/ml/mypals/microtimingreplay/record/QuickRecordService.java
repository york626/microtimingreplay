package ml.mypals.microtimingreplay.record;

import ml.mypals.microtimingreplay.MTRState;
import ml.mypals.microtimingreplay.MicroTimingReplay;
import ml.mypals.microtimingreplay.network.MTRNetworking;
import ml.mypals.microtimingreplay.network.MTRPayloads;
import ml.mypals.microtimingreplay.profile.MTRProfile;
import ml.mypals.microtimingreplay.profile.ProfileManager;
import ml.mypals.microtimingreplay.profile.TickFrame;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 「动态记录」的服务端实现：把「选区 → 建配置档 → 加区域 → 开录」这四步合成一次调用。
 *
 * <p>故意不新造数据格式：仍然用 {@link MTRProfile} / {@link ProfileManager} / {@link MTRState}
 * 那套东西，所以以前用命令录的档、以后用 GUI 录的档，回放器一视同仁。
 *
 * <p>规则（Ray 2026-10-22 定的）：
 * <ul>
 *   <li>配置档名默认取选区名，同名**覆盖**（每次点「动态记录」先清空再录）；</li>
 *   <li>手动停录；单次上限 {@link #MAX_TICKS} 刻，到点自动停，防止手滑录爆内存；</li>
 *   <li>选区体积上限 {@link #MAX_VOLUME} 格（和工具箱「动态追踪」同一口径）。</li>
 * </ul>
 */
public final class QuickRecordService {
    /** 单次录制上限：5000 刻（≈4 分钟游戏时间）。到点自动停。 */
    public static final int MAX_TICKS = 5000;
    /** 选区体积上限，超过直接拒绝。 */
    public static final long MAX_VOLUME = 1_000_000L;

    /** HUD 刷新间隔（刻）—— 每刻都发包太吵，10 刻（0.5 秒）足够跟手。 */
    private static final int BROADCAST_EVERY = 10;

    private static String activeName = null;
    private static int lastBroadcastTick = -1;
    private static boolean wasRecording = false;

    private QuickRecordService() {
    }

    /** 回给客户端的一帧状态。 */
    public record State(boolean recording, String profile, int ticks, int events, int limit, String message) {
        static State idle(String message) {
            return new State(false, "", 0, 0, MAX_TICKS, message);
        }
    }

    // ── 开始 / 停止 ──────────────────────────────────────────────────────────

    public static State start(ServerPlayer player, String rawName, BlockPos a, BlockPos b) {
        if (a == null || b == null) {
            return State.idle("选区无效（没拿到两个角点）");
        }
        if (MTRState.getCurrentState() == MTRState.State.RECORDING) {
            return State.idle("正在录制中，先点「停止记录」");
        }
        long volume = volume(a, b);
        if (volume > MAX_VOLUME) {
            return State.idle("选区太大（" + volume + " 格，上限 " + MAX_VOLUME + "）");
        }

        // 名字**跟着投影选区走**（Ray 2026-10-23：录制就是拿选区框的，名字正好联动），
        // 只做三件事：去掉非法字符、合并连续下划线、截到 20 字；选不出名字才退回 recN。
        String areaName = sanitizeName(rawName);
        String name = areaName.isEmpty() ? nextProfileName() : areaName;
        String dimension = player.serverLevel().dimension().location().toString();
        MinecraftServer server = player.serverLevel().getServer();
        if (server == null) {
            return State.idle("拿不到服务端（只支持单人/局域网）");
        }

        // 同名覆盖：先删旧的，避免把上一轮的帧接着往后追加
        if (ProfileManager.hasProfile(name)) {
            if (!ProfileManager.deleteProfile(name)) {
                return State.idle("覆盖旧配置档失败：" + name);
            }
        }
        MTRProfile profile = ProfileManager.createProfile(name);
        if (profile == null) {
            return State.idle("创建配置档失败：" + name);
        }
        if (areaName.length() > 24) {
            areaName = areaName.substring(0, 24);
        }
        profile.addArea(areaName.isBlank() ? "选区" : areaName, a, b, dimension);
        ProfileManager.saveProfile(profile);

        if (!MTRState.startRecording(name, server, MAX_TICKS)) {
            return State.idle("开始录制失败（可能正在回放，或已经在录）");
        }
        activeName = name;
        lastBroadcastTick = -1;
        MicroTimingReplay.LOGGER.info("[mtr] 动态记录开始：profile={} dim={} min={} max={} volume={}",
                name, dimension, a, b, volume);
        return new State(true, name, 0, 0, MAX_TICKS,
                "开始录制：" + name + "（上限 " + MAX_TICKS + " 刻；建议先 /tick freeze）");
    }

    public static State stop(ServerPlayer player) {
        if (MTRState.getCurrentState() != MTRState.State.RECORDING) {
            return State.idle("当前不在录制中");
        }
        State finalState = capture("已保存");
        MTRState.stopRecording();
        String name = finalState.profile();
        activeName = null;
        MicroTimingReplay.LOGGER.info("[mtr] 动态记录停止：profile={} ticks={} events={}",
                name, finalState.ticks(), finalState.events());
        return new State(false, name, finalState.ticks(), finalState.events(), MAX_TICKS,
                "已保存：" + name + "（" + finalState.ticks() + " 刻 / " + finalState.events() + " 个事件）");
    }

    // ── 每刻推进 ─────────────────────────────────────────────────────────────

    /**
     * 由 {@code END_SERVER_TICK} 调用：定期把状态推给有能力录制的玩家，
     * 并在「自动到点停录」时补发一次收尾消息。
     */
    public static void tick(MinecraftServer server) {
        boolean recording = MTRState.getCurrentState() == MTRState.State.RECORDING;

        if (wasRecording && !recording) {
            // 到点自动停了（checkAutoStop 干的）——这里只补状态，不重复保存
            State s = capture("已到上限，自动停止并保存");
            activeName = null;
            broadcast(server, s);
            wasRecording = false;
            return;
        }
        wasRecording = recording;
        if (!recording) {
            return;
        }
        int now = server.getTickCount();
        if (lastBroadcastTick >= 0 && now - lastBroadcastTick < BROADCAST_EVERY) {
            return;
        }
        lastBroadcastTick = now;
        broadcast(server, capture(""));
    }

    /** 只读快照（不发包）。空闲时也带上「最近一份配置档」，GUI 的「回放」按钮才知道该放哪个。 */
    public static State capture(String message) {
        if (MTRState.getCurrentState() != MTRState.State.RECORDING || MTRState.getActiveProfile() == null) {
            String latest = latestProfileName();
            if (latest.isEmpty()) {
                return State.idle(message);
            }
            MTRProfile profile = ProfileManager.loadProfile(latest);
            return new State(false, latest,
                    profile == null ? 0 : profile.getTicksRecorded(),
                    profile == null ? 0 : countEvents(profile),
                    MAX_TICKS, message);
        }
        MTRProfile profile = MTRState.getActiveProfile();
        int ticks = (int) Math.max(0,
                MicroTimingReplay.server.getTickCount() - MTRState.getRecordStartTick());
        return new State(true, profile.getName(), ticks, countEvents(profile), MAX_TICKS, message);
    }

    /** 单个玩家推送一次当前状态（进服时用，让 GUI 立刻知道最近录过什么）。 */
    public static void pushTo(ServerPlayer player) {
        if (player == null || !MTRNetworking.isAllowed(player)) return;
        State state = capture("");
        ServerPlayNetworking.send(player, new MTRPayloads.QuickRecordStateS2C(
                state.recording(), state.profile(), state.ticks(), state.events(),
                state.limit(), state.message()));
    }

    /** 磁盘上最近改动的那份配置档（没有就返回空串）。 */
    private static String latestProfileName() {
        MinecraftServer server = MicroTimingReplay.server;
        if (server == null) return "";
        String best = "";
        long bestTime = -1;
        for (String name : ProfileManager.listProfiles()) {
            java.io.File file = ml.mypals.microtimingreplay.profile.WorldScopedStorage.getProfileFile(name);
            if (file != null && file.exists() && file.lastModified() > bestTime) {
                bestTime = file.lastModified();
                best = name;
            }
        }
        return best;
    }

    private static void broadcast(MinecraftServer server, State state) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (MTRNetworking.isAllowed(player)) {
                ServerPlayNetworking.send(player, new MTRPayloads.QuickRecordStateS2C(
                        state.recording(), state.profile(), state.ticks(), state.events(),
                        state.limit(), state.message()));
            }
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    private static int countEvents(MTRProfile profile) {
        int total = 0;
        for (TickFrame frame : profile.getFrames()) {
            total += frame.getEvents().size();
        }
        return total;
    }

    private static long volume(BlockPos a, BlockPos b) {
        return (long) (Math.abs(a.getX() - b.getX()) + 1)
                * (Math.abs(a.getY() - b.getY()) + 1)
                * (Math.abs(a.getZ() - b.getZ()) + 1);
    }

    /** 下一份可用的自动编号名：rec1 / rec2 / …（跳过已存在的）。 */
    private static String nextProfileName() {
        java.util.Set<String> used = new java.util.HashSet<>(ProfileManager.listProfiles());
        for (int i = 1; i < 100000; i++) {
            String candidate = "rec" + i;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return "rec" + (System.currentTimeMillis() % 100000);
    }

    /**
     * 配置档名：**允许中文**（这是我们的 fork，文件名/命令都能吃），只把路径不友好的字符换成下划线。
     * 上限 20 字，够认出是哪台机器，又不至于把面板撑爆。
     */
    private static String sanitizeName(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceAll("[^\\p{L}\\p{N}_.+-]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (s.length() > 20) {
            s = s.substring(0, 20);
        }
        return s;
    }
}
