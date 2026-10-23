package ml.mypals.microtimingreplay.client;

import ml.mypals.microtimingreplay.network.MTRPayloads;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.List;

/** 服务端推下来的「可回放录制」列表，回放选择页读它。 */
@Environment(EnvType.CLIENT)
public final class ClientProfileList {

    private static List<MTRPayloads.ProfileEntry> entries = List.of();
    private static long revision = 0;

    private ClientProfileList() {
    }

    public static void set(List<MTRPayloads.ProfileEntry> list) {
        entries = list == null ? List.of() : list;
        revision++;
    }

    public static List<MTRPayloads.ProfileEntry> entries() {
        return entries;
    }

    public static long revision() {
        return revision;
    }

    public static void reset() {
        entries = List.of();
        revision++;
    }
}
