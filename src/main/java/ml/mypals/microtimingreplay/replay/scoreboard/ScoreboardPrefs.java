package ml.mypals.microtimingreplay.replay.scoreboard;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 「时间轴侧栏（记分板）显示 / 隐藏」——按玩家记，纯显示层，不影响回放本身。
 *
 * <p>隐藏时顺手把侧栏清掉（{@link VirtualScoreboardManager#removeScoreboard}），
 * 之后每步也就不再往他那儿推那些行。
 */
public final class ScoreboardPrefs {

    private static final Set<UUID> HIDDEN = new HashSet<>();

    private ScoreboardPrefs() {
    }

    public static boolean hidden(ServerPlayer player) {
        return player != null && HIDDEN.contains(player.getUUID());
    }

    public static void set(ServerPlayer player, boolean hidden) {
        if (player == null) return;
        if (hidden) {
            HIDDEN.add(player.getUUID());
            VirtualScoreboardManager.removeScoreboard(player);
        } else {
            HIDDEN.remove(player.getUUID());
            // 只有在看回放时才重新挂 —— 否则会在没有回放的情况下凭空多出一个侧栏
            if (ml.mypals.microtimingreplay.replay.ReplayManager.subscribedSession(player) != null) {
                VirtualScoreboardManager.setupScoreboard(player);
            }
        }
    }

    /** 玩家退出时清一下，别留垃圾 UUID。 */
    public static void forget(ServerPlayer player) {
        if (player != null) HIDDEN.remove(player.getUUID());
    }
}
