package ml.mypals.microtimingreplay.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 读 Litematica 的当前选区（软依赖，**走反射**）。
 *
 * <p>为什么要反射而不是直接 import：这个仓库是公开的，把 Litematica 的 jar 塞进
 * {@code libs/} 再提交有许可证问题，而 GitHub Actions 上没有那个 jar 又会让 CI 编译失败。
 * 反射一次只碰 4 个稳定方法（{@code DataManager.getSelectionManager}、
 * {@code SelectionManager.getCurrentSelection}、{@code AreaSelection.getAllSubRegionBoxes} /
 * {@code getName}、{@code Box.getPos1/getPos2}），坏了也只是「读不到选区」，不会崩游戏。
 */
@Environment(EnvType.CLIENT)
public final class LitematicaSelection {

    /** 选区快照：两个角点 + 体积（用于提示与上限判断）。 */
    public record Selection(String name, BlockPos min, BlockPos max, long volume) {
    }

    private LitematicaSelection() {
    }

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    /** 拿不到选区（没装 Litematica / 没建选区 / 还没进世界）时返回 {@code null}。 */
    public static Selection capture() {
        if (!available()) {
            return null;
        }
        try {
            return Bridge.capture();
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Bridge {
        static Selection capture() throws Exception {
            Class<?> dataManager = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object selectionManager = dataManager.getMethod("getSelectionManager").invoke(null);
            if (selectionManager == null) {
                return null;
            }
            Object area = selectionManager.getClass().getMethod("getCurrentSelection").invoke(selectionManager);
            if (area == null) {
                return null;
            }

            Object rawBoxes = area.getClass().getMethod("getAllSubRegionBoxes").invoke(area);
            if (!(rawBoxes instanceof List<?> boxes)) {
                return null;
            }

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            boolean any = false;
            for (Object box : boxes) {
                Method getPos1 = box.getClass().getMethod("getPos1");
                Method getPos2 = box.getClass().getMethod("getPos2");
                if (!(getPos1.invoke(box) instanceof BlockPos p1) || !(getPos2.invoke(box) instanceof BlockPos p2)) {
                    continue;
                }
                any = true;
                minX = Math.min(minX, Math.min(p1.getX(), p2.getX()));
                minY = Math.min(minY, Math.min(p1.getY(), p2.getY()));
                minZ = Math.min(minZ, Math.min(p1.getZ(), p2.getZ()));
                maxX = Math.max(maxX, Math.max(p1.getX(), p2.getX()));
                maxY = Math.max(maxY, Math.max(p1.getY(), p2.getY()));
                maxZ = Math.max(maxZ, Math.max(p1.getZ(), p2.getZ()));
            }
            if (!any) {
                return null;
            }

            Object rawName = area.getClass().getMethod("getName").invoke(area);
            String name = rawName instanceof String s && !s.isBlank() ? s : "quick";
            long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            return new Selection(name, new BlockPos(minX, minY, minZ),
                    new BlockPos(maxX, maxY, maxZ), volume);
        }
    }
}
