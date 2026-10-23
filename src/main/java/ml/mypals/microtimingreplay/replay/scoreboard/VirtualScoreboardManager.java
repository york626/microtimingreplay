package ml.mypals.microtimingreplay.replay.scoreboard;

import ml.mypals.microtimingreplay.util.MTRComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.*;

public class VirtualScoreboardManager {
    private static final String OBJECTIVE_NAME = "mtr_timeline";
    private static final Map<UUID, Integer> lastLineCounts = new HashMap<>();

    /** 谁目前挂着我们的虚拟记分板 —— 重复 setup 会让客户端收到重复 objective 直接断线。 */
    private static final Set<UUID> ACTIVE = new HashSet<>();

    public static boolean isActive(ServerPlayer player) {
        return player != null && ACTIVE.contains(player.getUUID());
    }

    public static void setupScoreboard(ServerPlayer player) {
        if (player == null) return;
        if (!ACTIVE.add(player.getUUID())) {
            return;   // 已经挂着，别再发一次 add
        }
        Scoreboard dummy = new Scoreboard();
        Objective objective = new Objective(dummy, OBJECTIVE_NAME, ObjectiveCriteria.DUMMY, MTRComponent.translatable("mtr.scoreboard.title", "Replay Timeline"), ObjectiveCriteria.RenderType.INTEGER, false, null);
        
        player.connection.send(new ClientboundSetObjectivePacket(objective, 0));
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        lastLineCounts.put(player.getUUID(), 0);
    }

    public static void removeScoreboard(ServerPlayer player) {
        if (player == null) return;
        if (!ACTIVE.remove(player.getUUID())) {
            return;   // 本来就没挂，别发 remove
        }
        Scoreboard dummy = new Scoreboard();
        Objective objective = new Objective(dummy, OBJECTIVE_NAME, ObjectiveCriteria.DUMMY, MTRComponent.translatable("mtr.scoreboard.title", "Replay Timeline"), ObjectiveCriteria.RenderType.INTEGER, false, null);
        
        player.connection.send(new ClientboundSetObjectivePacket(objective, 1));
        lastLineCounts.remove(player.getUUID());
        
        // Restore server scoreboard if any
        Objective realSidebar = player.level().getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        if (realSidebar != null) {
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, realSidebar));
        }
    }

    public static void updateLines(ServerPlayer player, List<TimelineGenerator.ScoreLine> lines) {
        if (!lastLineCounts.containsKey(player.getUUID())) return;
        
        int lastCount = lastLineCounts.get(player.getUUID());
        int newCount = lines.size();
        
        // Remove lines that are no longer needed
        for (int i = newCount; i < lastCount; i++) {
            String fakePlayer = "line_" + i;
            player.connection.send(new ClientboundResetScorePacket(fakePlayer, OBJECTIVE_NAME));
        }

        // Add or update current lines
        for (int i = 0; i < newCount; i++) {
            String fakePlayer = "line_" + i;
            int score = newCount - i;
            TimelineGenerator.ScoreLine line = lines.get(i);
            Optional<NumberFormat> nf;
            if (line.stepIndex() != -1) {
                nf = Optional.of(new FixedFormat(Component.literal(String.valueOf(line.stepIndex())).withStyle(ChatFormatting.WHITE)));
            } else {
                nf = Optional.of(BlankFormat.INSTANCE);
            }
            player.connection.send(new ClientboundSetScorePacket(fakePlayer, OBJECTIVE_NAME, score, Optional.of(line.text()), nf));
        }
        
        lastLineCounts.put(player.getUUID(), newCount);
    }
}