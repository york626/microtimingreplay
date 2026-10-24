package ml.mypals.microtimingreplay.event;


import net.minecraft.core.registries.BuiltInRegistries;

import ml.mypals.microtimingreplay.util.MTRComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

public class MovingPistonTickEvent extends BlockPosEvent {

    public static final String TYPE = "movingPistonTick";
    private final float progress;
    private final int directionId;
    private final int movedBlockStateId;
    private final boolean extending;
    private final boolean isSourcePiston;

    public MovingPistonTickEvent(long tick, BlockPos pos, float progress, int movedBlockStateId,
            Direction direction, boolean extending, boolean isSourcePiston, String dimension) {
        super(tick, TYPE, pos, dimension);
        this.progress = progress;
        this.movedBlockStateId = movedBlockStateId;
        this.directionId = direction.get3DDataValue();
        this.extending = extending;
        this.isSourcePiston = isSourcePiston;
    }

    public Direction getDirection() {
        return Direction.from3DDataValue(directionId);
    }

    public boolean isExtending() {
        return extending;
    }

    public boolean isSourcePiston() {
        return isSourcePiston;
    }

    /** 正在移动的方块状态（兜底画 B36 替身时用）。 */
    public int getMovedBlockStateId() {
        return movedBlockStateId;
    }


    @Override
    public String filterId() {
        return "moving_piston_tick";
    }

    @Override
    public MutableComponent fillHoverText() {
        BlockState movedState = Block.stateById(movedBlockStateId);
        String blockKey = BuiltInRegistries.BLOCK.getKey(movedState.getBlock()).toString();

        Component opText = MTRComponent.translatable(extending ? "mtr.tooltip.piston.push" : "mtr.tooltip.piston.pull", extending ? "PUSH (Extending)" : "PULL (Retracting)");

        MutableComponent text = MTRComponent.translatable("mtr.tooltip.moving_piston_tick_title", "Moving Piston Tick @ [%d, %d, %d]", getX(), getY(), getZ())
                .append(Component.literal("\n")).withStyle(ChatFormatting.LIGHT_PURPLE);

        if (getDimension() != null && !getDimension().isEmpty()) {
            text.append(MTRComponent.translatable("mtr.tooltip.dimension", "Dimension: %s", getDimension()).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\n"));
        }

        return text
                .append(MTRComponent.translatable("mtr.tooltip.progress", "Progress: %.1f%%", progress * 100.0f).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\n"))
                .append(MTRComponent.translatable("mtr.tooltip.operation", "Operation: %s", opText.getString()).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\n"))
                .append(MTRComponent.translatable("mtr.tooltip.facing", "Facing: %s", getDirection().getName().toUpperCase()).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\n"))
                .append(MTRComponent.translatable("mtr.tooltip.moved_block", "Moved Block: %s", blockKey).withStyle(ChatFormatting.AQUA));
    }


    @Override
    public ChatFormatting getColor() {
        return ChatFormatting.LIGHT_PURPLE;
    }

    @Override
    public MutableComponent getScoreboardText() {
        return appendPosText(MTRComponent.translatable("mtr.scoreboard.event.leaf.pistontick",
                "Piston Tick")
        );
    }

    @Override
    public void display(ServerLevel level, Vector3f scale) {
        // B36 已由 ReplaySession 每步「提前落位」成真实方块，替身动画（按进度挪展示体）不再需要；
        // 这里回到通用玻璃块高亮，点时间轴行时和其它事件一致。
        super.display(level, scale);
    }

    @Override
    public CompoundTag writeNBT() {
        CompoundTag tag = super.writeNBT();
        tag.putFloat("progress", progress);
        tag.putInt("directionId", directionId);
        tag.putInt("stateId", movedBlockStateId);
        tag.putBoolean("extending", extending);
        tag.putBoolean("isSourcePiston", isSourcePiston);
        return tag;
    }

    public static MovingPistonTickEvent readNBT(CompoundTag tag) {
        MovingPistonTickEvent event = new MovingPistonTickEvent(
                tag.getLong("tick"),
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                tag.getFloat("progress"),
                tag.contains("stateId") ? tag.getInt("stateId") : tag.getInt("movedBlockStateId"),
                Direction.from3DDataValue(tag.getInt("directionId")),
                tag.getBoolean("extending"),
                tag.getBoolean("isSourcePiston"),
                tag.getString("dimension")
        );
        MTREvent.readChildrenNBT(event, tag);
        return event;
    }
}
