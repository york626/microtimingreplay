package ml.mypals.microtimingreplay.marker;

import ml.mypals.microtimingreplay.util.DisplayUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public final class MarkerManager {

    public static void clearAll(ServerLevel level, String sessionId) {
        if (level == null) return;
        String sessionTag = sessionId == null ? null : MTRMarker.sessionTag(sessionId);
        Iterable<ServerLevel> levels = level.getServer().getAllLevels();
        for (ServerLevel sl : levels) {
            List<Entity> toDiscard = new ArrayList<>();
            for (Entity entity : sl.getAllEntities()) {
                if (entity instanceof Display) {
                    if (entity.getTags().contains("mtr_piston_display")) continue;
                    boolean marker = entity.getTags().contains("mtr_replay_marker")
                            || entity.getTags().contains("mtr_marker");
                    if (!marker) continue;

                    if (sessionTag != null) {
                        // 带"别的会话"tag 的别动；本会话的、以及**完全没有会话 tag 的孤儿**
                        // （旧版本/无上下文时生成的）都清 —— 孤儿不清就会永远挂在世界里。
                        boolean belongsToOtherSession = entity.getTags().stream()
                                .anyMatch(tag -> tag.startsWith(DisplayUtils.SESSION_TAG_PREFIX))
                                && !entity.getTags().contains(sessionTag);
                        if (belongsToOtherSession) continue;
                    }
                    toDiscard.add(entity);
                }
            }
            for (Entity entity : toDiscard) {
                entity.discard();
            }
        }
    }
}