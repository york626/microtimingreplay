package ml.mypals.microtimingreplay.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 客户端这一份「动态记录」状态：HUD 和快捷录制界面都读它，数据来自服务端的
 * {@code QuickRecordStateS2C}。客户端自己从不判断"录没录"——服务端说了算。
 */
@Environment(EnvType.CLIENT)
public final class QuickRecordState {

    public static final int DEFAULT_LIMIT = 5000;

    private static boolean recording;
    private static String profile = "";
    private static int ticks;
    private static int events;
    private static int limit = DEFAULT_LIMIT;
    /** 服务端给玩家的一句话（成功 / 失败原因），界面上显示一次即可。 */
    private static String message = "";

    private QuickRecordState() {
    }

    public static void update(boolean isRecording, String profileName, int recordedTicks,
                              int eventCount, int tickLimit, String serverMessage) {
        recording = isRecording;
        profile = profileName == null ? "" : profileName;
        ticks = recordedTicks;
        events = eventCount;
        limit = tickLimit > 0 ? tickLimit : DEFAULT_LIMIT;
        message = serverMessage == null ? "" : serverMessage;
    }

    public static void reset() {
        recording = false;
        profile = "";
        ticks = 0;
        events = 0;
        limit = DEFAULT_LIMIT;
        message = "";
    }

    public static boolean isRecording() {
        return recording;
    }

    public static String profile() {
        return profile;
    }

    public static int ticks() {
        return ticks;
    }

    public static int events() {
        return events;
    }

    public static int limit() {
        return limit;
    }

    public static String message() {
        return message;
    }

    public static void clearMessage() {
        message = "";
    }
}
