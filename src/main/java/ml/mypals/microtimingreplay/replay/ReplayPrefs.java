package ml.mypals.microtimingreplay.replay;

/**
 * 回放期的开关（全局、单人用）。
 *
 * <p>{@code skipItemEntities}：回放时**不重建掉落物实体**。活塞/机器运行时掉出来的物品
 * 对分析时序没意义，而且会堆一地（Ray 2026-10-23 明确不要它们）。
 * 只影响 replay 生成的"替身实体"，不动世界备份里原本就有的实体。
 */
public final class ReplayPrefs {

    private static volatile boolean skipItemEntities = true;
    /** 回放时不重建"玩家替身"（玩家被录成穿头盔的盔甲架，站在机器里很碍眼）。 */
    private static volatile boolean skipPlayerStandIns = true;
    /** 标记只画当前这一步的目标方块，不画作用域链（父级标记叠起来会把机器糊成一片半透明）。 */
    private static volatile boolean markersLeafOnly = true;
    /** 录制时自动精简：关掉网络包/实体 tick/实体移动这些噪音事件，只留机器流程与计划刻。 */
    private static volatile boolean cleanRecording = true;
    /**
     * 要不要生成"方块标记"（那些玻璃展示体）。
     * 默认**关**：游戏事件/活塞/结构这类标记会生成很大的玻璃盒子，把机器糊住；
     * 机器本身的状态变化在回放里是真实方块，不需要外加玻璃。想看得更花再打开。
     */
    private static volatile boolean showBlockMarkers = false;

    private ReplayPrefs() {
    }

    public static boolean skipItemEntities() {
        return skipItemEntities;
    }

    public static void setSkipItemEntities(boolean value) {
        skipItemEntities = value;
    }

    public static boolean skipPlayerStandIns() {
        return skipPlayerStandIns;
    }

    public static void setSkipPlayerStandIns(boolean value) {
        skipPlayerStandIns = value;
    }

    public static boolean markersLeafOnly() {
        return markersLeafOnly;
    }

    public static void setMarkersLeafOnly(boolean value) {
        markersLeafOnly = value;
    }

    public static boolean cleanRecording() {
        return cleanRecording;
    }

    public static void setCleanRecording(boolean value) {
        cleanRecording = value;
    }

    public static boolean showBlockMarkers() {
        return showBlockMarkers;
    }

    public static void setShowBlockMarkers(boolean value) {
        showBlockMarkers = value;
    }
}
