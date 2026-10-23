package ml.mypals.microtimingreplay.marker;

import net.minecraft.world.entity.Entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Brightness;
import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ml.mypals.microtimingreplay.replay.ReplayContext;

public class PistonDisplayManager {

    private static final Map<String, Map<BlockPos, List<UUID>>> displaysBySession = new HashMap<>();
    private static final Map<String, Map<BlockPos, List<UUID>>> stagedBySession = new HashMap<>();

    private static Map<BlockPos, List<UUID>> pistonDisplays() {
        return displaysBySession.computeIfAbsent(ReplayContext.key(), k -> new HashMap<>());
    }

    private static Map<BlockPos, List<UUID>> stagedOldPistonDisplays() {
        return stagedBySession.computeIfAbsent(ReplayContext.key(), k -> new HashMap<>());
    }

    // Drops everything a session owned
    public static void forgetSession(String sessionId) {
        String key = sessionId == null ? "" : sessionId;
        displaysBySession.remove(key);
        stagedBySession.remove(key);
    }

    public static void beginRenderPistons() {
        stagedOldPistonDisplays().clear();
        stagedOldPistonDisplays().putAll(pistonDisplays());
        pistonDisplays().clear();
    }

    private static Entity findEntityInAllLevels(ServerLevel defaultLevel, UUID uuid) {
        if (defaultLevel == null || uuid == null) return null;
        if (defaultLevel.getServer() != null) {
            for (ServerLevel sl : defaultLevel.getServer().getAllLevels()) {
                Entity e = sl.getEntity(uuid);
                if (e != null) return e;
            }
        }
        return defaultLevel.getEntity(uuid);
    }

    public static void endRenderPistons(ServerLevel level) {
        List<Entity> toDiscard = new ArrayList<>();
        for (List<UUID> uuids : stagedOldPistonDisplays().values()) {
            for (UUID uuid : uuids) {
                Entity entity = findEntityInAllLevels(level, uuid);
                if (entity != null) toDiscard.add(entity);
            }
        }
        for (Entity e : toDiscard) {
            e.discard();
        }
        stagedOldPistonDisplays().clear();
    }

    public static void clearAll(ServerLevel level) {
        List<Entity> toDiscard = new ArrayList<>();
        for (List<UUID> uuids : pistonDisplays().values()) {
            for (UUID uuid : uuids) {
                Entity entity = findEntityInAllLevels(level, uuid);
                if (entity != null) toDiscard.add(entity);
            }
        }
        for (Entity e : toDiscard) {
            e.discard();
        }
        pistonDisplays().clear();
        stagedOldPistonDisplays().clear();
    }

    public static void clearGlows(ServerLevel level) {
        for (List<UUID> uuids : pistonDisplays().values()) {
            for (UUID uuid : uuids) {
                Entity entity = findEntityInAllLevels(level, uuid);
                if (entity != null) entity.setGlowingTag(false);
            }
        }
    }

    public static List<UUID> claimOldPistonDisplay(BlockPos pos) {
        return stagedOldPistonDisplays().remove(pos);
    }

    /**
     * 这批 UUID 里还有没有「活着」的展示体。
     *
     * <p>光有 UUID 不够：展示体可能已经被一键清理 / 换维度 / 区块卸载弄没了，这时候再把它登记一次
     * 就等于"占了位置却不画图" ⇒ 画面上就是一片空洞（Ray 看到的透明方块）。
     */
    public static boolean anyLive(ServerLevel level, List<UUID> uuids) {
        if (level == null || uuids == null) return false;
        for (UUID uuid : uuids) {
            if (uuid != null && findEntityInAllLevels(level, uuid) != null) return true;
        }
        return false;
    }

    /** 当前这一轮登记了多少个活塞替身（诊断用）。 */
    public static int registeredCount() {
        return pistonDisplays().size();
    }

    public static void registerPistonDisplays(BlockPos pos, List<UUID> uuids) {
        pistonDisplays().put(pos, new ArrayList<>(uuids));
    }

    public static void removePistonDisplays(BlockPos pos, ServerLevel level) {
        List<UUID> uuids = pistonDisplays().remove(pos);
        if (uuids == null) {
            uuids = stagedOldPistonDisplays().remove(pos);
        }
        if (uuids == null) return;
        for (UUID uuid : uuids) {
            Entity entity = findEntityInAllLevels(level, uuid);
            if (entity != null) entity.discard();
        }
    }

    public static List<UUID> getPistonDisplayUUIDs(BlockPos pos) {
        return pistonDisplays().get(pos);
    }

    public static UUID spawnStaticBlockDisplay(ServerLevel level, BlockPos blockPos, BlockState state, float xOff, float yOff, float zOff) {
        // ⚠️ 这里**不能**受 showBlockMarkers 控制：活塞在推拉中途是 `moving_piston` 方块，
        // 它自己渲染成空气，全靠这个替身把"正在移动的方块"画出来。关掉就变成一片空洞/透明。
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        entity.setPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        entity.setBlockState(state);
        entity.setBrightnessOverride(new Brightness(15, 15));
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setSilent(true);

        Transformation transform = new Transformation(
                new Vector3f(xOff, yOff, zOff),
                new Quaternionf(),
                new Vector3f(1, 1, 1),
                new Quaternionf()
        );
        entity.setTransformation(transform);
        entity.setTransformationInterpolationDuration(0);
        entity.setTransformationInterpolationDelay(0);

        entity.addTag("mtr_piston_display");
        entity.addTag("mtr_replay_marker");
        entity.setGlowingTag(false);
        // 默认 viewRange=1（64 格），给足一点余量，免得大机器边缘的替身被视距判定挡掉
        entity.setViewRange(10.0f);
        level.addFreshEntity(entity);
        return entity.getUUID();
    }

    public static void applyOffset(Display.BlockDisplay bd, float xOff, float yOff, float zOff) {
        Transformation transform = new Transformation(
                new Vector3f(xOff, yOff, zOff),
                new Quaternionf(),
                new Vector3f(1, 1, 1),
                new Quaternionf()
        );
        bd.setTransformation(transform);
        bd.setTransformationInterpolationDuration(0);
        bd.setTransformationInterpolationDelay(0);
    }
}
