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
}
