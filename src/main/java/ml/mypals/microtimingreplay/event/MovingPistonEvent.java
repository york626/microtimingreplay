package ml.mypals.microtimingreplay.event;


import net.minecraft.core.registries.BuiltInRegistries;

import ml.mypals.microtimingreplay.marker.PistonDisplayManager;
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

public class MovingPistonEvent extends BlockPosEvent {

    public static final String TYPE = "movingPiston";
    private final int movedBlockStateId;
    private final int directionId;
    private final boolean extending;
    private final boolean isSourcePiston;
    private final boolean despawn;

    public MovingPistonEvent(long tick, BlockPos pos, int movedBlockStateId, Direction direction,
                              boolean extending, boolean isSourcePiston, boolean despawn, String dimension) {
        super(tick, TYPE, pos, dimension);
        this.movedBlockStateId = movedBlockStateId;
        this.directionId = direction.get3DDataValue();
        this.extending = extending;
        this.isSourcePiston = isSourcePiston;
        this.despawn = despawn;
    }


    public Direction getDirection() { return Direction.from3DDataValue(directionId); }
    public boolean isExtending() { return extending; }
    public boolean isSourcePiston() { return isSourcePiston; }
    public boolean isDespawn() { return despawn; }
    /** 正在移动的方块状态（B36 位置要画出来的那个方块）。 */
    public int getMovedBlockStateId() { return movedBlockStateId; }


    @Override
    public String filterId() {
        return isDespawn() ? "moving_piston_despawn" : "moving_piston_start";
    }

    @Override
    public ChatFormatting getColor() {
        return ChatFormatting.LIGHT_PURPLE;
    }

    @Override
    public MutableComponent fillHoverText() {
        BlockState movedState = Block.stateById(movedBlockStateId);
        String blockKey = BuiltInRegistries.BLOCK.getKey(movedState.getBlock()).toString();

        Component spawnText = MTRComponent.translatable(despawn ? "mtr.tooltip.piston.despawn" : "mtr.tooltip.piston.spawn", despawn ? "[Despawn]" : "[Spawn]");
        Component opText = MTRComponent.translatable(extending ? "mtr.tooltip.piston.push" : "mtr.tooltip.piston.pull", extending ? "PUSH (Extending)" : "PULL (Retracting)");
        Component roleText = MTRComponent.translatable(isSourcePiston ? "mtr.tooltip.piston.role_base" : "mtr.tooltip.piston.role_block", isSourcePiston ? "Piston Head / Base" : "Moved Structure Block");

        MutableComponent text = MTRComponent.translatable("mtr.tooltip.moving_piston_title", "Moving Piston %s @ [%d, %d, %d]", spawnText.getString(), getX(), getY(), getZ())
                .append(Component.literal("\n")).withStyle(ChatFormatting.LIGHT_PURPLE);

        if (getDimension() != null && !getDimension().isEmpty()) {
            text.append(MTRComponent.translatable("mtr.tooltip.dimension", "Dimension: %s", getDimension()).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\n"));
        }

        return text
                .append(MTRComponent.translatable("mtr.tooltip.operation", "Operation: %s", opText.getString()).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\n"))
                .append(MTRComponent.translatable("mtr.tooltip.facing", "Facing: %s", getDirection().getName().toUpperCase()).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\n"))
                .append(MTRComponent.translatable("mtr.tooltip.moved_block", "Moved Block: %s", blockKey).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\n"))
                .append(MTRComponent.translatable("mtr.tooltip.role", "Role: %s", roleText.getString()).withStyle(ChatFormatting.WHITE));
    }

    @Override
    public MutableComponent getScoreboardText() {
        String key = despawn ? "mtr.scoreboard.event.leaf.pistonremove" : "mtr.scoreboard.event.leaf.pistonspawn";
        String fallback = despawn ? "Piston Remove" : "Piston Spawn";
        return appendPosText(MTRComponent.translatable(key, fallback));
    }

    @Override
    public void display(ServerLevel level, Vector3f scale) {
        // B36 已由 ReplaySession 每步「提前落位」成真实方块（settleMovingPistons），
        // 不再生成替身展示体——那是 1.3.10~1.3.18 反复修不好的渲染链；这里回到通用玻璃块高亮。
        if (isDespawn()) {
            // 兼容清理：旧版本会话若还留着替身展示体，顺手删掉，别叠在真方块上
            PistonDisplayManager.removePistonDisplays(getPos(), level);
        }
        super.display(level, scale);
    }

    @Override
    public CompoundTag writeNBT() {
        CompoundTag tag = super.writeNBT();
        tag.putInt("stateId", movedBlockStateId);
        tag.putInt("directionId", directionId);
        tag.putBoolean("extending", extending);
        tag.putBoolean("isSourcePiston", isSourcePiston);
        tag.putBoolean("despawn", isDespawn());
        return tag;
    }

    public static MovingPistonEvent readNBT(CompoundTag tag) {
        MovingPistonEvent event = new MovingPistonEvent(
                tag.getLong("tick"),
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                tag.contains("stateId") ? tag.getInt("stateId") : tag.getInt("movedBlockStateId"),
                Direction.from3DDataValue(tag.getInt("directionId")),
                tag.getBoolean("extending"),
                tag.getBoolean("isSourcePiston"),
                tag.getBoolean("despawn"),
                tag.getString("dimension")
        );
        MTREvent.readChildrenNBT(event, tag);
        return event;
    }
}
