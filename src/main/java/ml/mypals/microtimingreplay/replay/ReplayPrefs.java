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

    private ReplayPrefs() {
    }

    public static boolean skipItemEntities() {
        return skipItemEntities;
    }

    public static void setSkipItemEntities(boolean value) {
        skipItemEntities = value;
    }
}
