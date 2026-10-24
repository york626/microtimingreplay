package ml.mypals.microtimingreplay.replay;


import ml.mypals.microtimingreplay.MicroTimingReplay;
import ml.mypals.microtimingreplay.profile.MTRProfile;
import ml.mypals.microtimingreplay.util.MTRBlockFlags;
import ml.mypals.microtimingreplay.util.PlayerProxy;
import ml.mypals.microtimingreplay.profile.WorldScopedStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WorldBackupManager {
    public static void init() {
        // Backups live in the profile's own directory; nothing to lay out up front.
    }

    public static void backup(MTRProfile profile, String suffix, boolean includePlayerStandIns) {
        if (MicroTimingReplay.server == null) return;
        
        CompoundTag rootTag = new CompoundTag();
        ListTag areasTag = new ListTag();

        for (MTRProfile.Area area : profile.getAreas()) {
            ServerLevel level = null;
            for (ServerLevel sl : MicroTimingReplay.server.getAllLevels()) {
                if (sl.dimension().location().toString().equals(area.dimension)) {
                    level = sl;
                    break;
                }
            }
            if (level == null) continue;

            CompoundTag areaTag = new CompoundTag();
            areaTag.putString("name", area.name);
            areaTag.putString("dimension", area.dimension);
            
            ListTag blocksTag = new ListTag();
            
            int minX = Math.min(area.x1, area.x2);
            int minY = Math.min(area.y1, area.y2);
            int minZ = Math.min(area.z1, area.z2);
            int maxX = Math.max(area.x1, area.x2);
            int maxY = Math.max(area.y1, area.y2);
            int maxZ = Math.max(area.z1, area.z2);
            
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        BlockEntity be = level.getBlockEntity(pos);

                        CompoundTag blockTag = new CompoundTag();
                        blockTag.putInt("x", x);
                        blockTag.putInt("y", y);
                        blockTag.putInt("z", z);

                        // moving_piston（B36）不能带着方块实体进快照：回放若还原出一个活的
                        // PistonMovingBlockEntity，世界一旦在走刻（未冻结）它会自己 tick 完结——
                        // 更新邻居、和回放写入打架，甚至走 updateOrDestroy → destroyBlock(掉落)
                        // 平白掉出活塞物品。按原版完结语义落位成它正在搬运的方块（同一格）。
                        BlockState savedState = state;
                        CompoundTag savedNbt = null;
                        if (state.is(Blocks.MOVING_PISTON)) {
                            if (be instanceof PistonMovingBlockEntity piston
                                    && piston.getMovedState() != null
                                    && !piston.getMovedState().isAir()) {
                                savedState = piston.getMovedState();
                            }
                            // 无论换不换状态，都不保存这份方块实体
                        } else if (be != null) {
                            savedNbt = be.saveWithFullMetadata(level.registryAccess());
                        }
                        blockTag.putInt("stateId", Block.getId(savedState));
                        if (savedNbt != null) {
                            blockTag.put("nbt", savedNbt);
                        }

                        blocksTag.add(blockTag);
                    }
                }
            }
            
            areaTag.put("blocks", blocksTag);

            // Backup >>non-player<< real entities in area
            ListTag entitiesTag = new ListTag();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Player && !includePlayerStandIns) continue;
                if (entity.getTags().contains(EntityReplayManager.REPLAY_ENTITY_TAG)) continue;

                double ex = entity.getX();
                double ey = entity.getY();
                double ez = entity.getZ();

                if (ex >= minX && ex <= maxX + 1 && ey >= minY && ey <= maxY + 1 && ez >= minZ && ez <= maxZ + 1) {
                    // Players come out of this as mannequin NBT, so nothing that is
                    // written here can ever be restored as a real player.
                    entitiesTag.add(PlayerProxy.snapshotNbt(entity, level.registryAccess()));
                }
            }
            areaTag.put("entities", entitiesTag);

            areasTag.add(areaTag);
        }
        
        rootTag.put("areas", areasTag);
        
        Path path = WorldScopedStorage.getBackupFile(profile.getName(), suffix).toPath();
        try {
            NbtIo.writeCompressed(rootTag, path);
        } catch (IOException e) {
            MicroTimingReplay.LOGGER.error("Failed to backup world for profile: {}", profile.getName(), e);
        }
    }

    public static void restore(MTRProfile profile, String suffix, boolean asStandIns) {
        if (MicroTimingReplay.server == null) return;

        Path path = WorldScopedStorage.getBackupFile(profile.getName(), suffix).toPath();
        if (!path.toFile().exists()) return;
        
        try {
            CompoundTag rootTag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ListTag areasTag = rootTag.getList("areas", Tag.TAG_COMPOUND); 
            
            for (int i = 0; i < areasTag.size(); i++) {
                CompoundTag areaTag = areasTag.getCompound(i);
                String dimension = areaTag.getString("dimension");
                
                ServerLevel level = null;
                for (ServerLevel sl : MicroTimingReplay.server.getAllLevels()) {
                    if (sl.dimension().location().toString().equals(dimension)) {
                        level = sl;
                        break;
                    }
                }
                if (level == null) continue;
                
                ListTag blocksTag = areaTag.getList("blocks", Tag.TAG_COMPOUND);

                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

                for (int j = 0; j < blocksTag.size(); j++) {
                    CompoundTag blockTag = blocksTag.getCompound(j);
                    int x = blockTag.getInt("x");
                    int y = blockTag.getInt("y");
                    int z = blockTag.getInt("z");
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }

                // Clear current >>non-player<< real entities in area before restoring
                if (minX <= maxX) {
                    List<Entity> toRemove = new ArrayList<>();
                    for (Entity entity : level.getAllEntities()) {
                        if (entity instanceof Player) continue;
                        if (entity.getTags().contains(EntityReplayManager.REPLAY_ENTITY_TAG)) continue;

                        double ex = entity.getX();
                        double ey = entity.getY();
                        double ez = entity.getZ();

                        if (ex >= minX && ex <= maxX + 1 && ey >= minY && ey <= maxY + 1 && ez >= minZ && ez <= maxZ + 1) {
                            toRemove.add(entity);
                        }
                    }
                    for (Entity e : toRemove) {
                        e.discard();
                    }
                }

                // Restore blocks
                for (int j = 0; j < blocksTag.size(); j++) {
                    CompoundTag blockTag = blocksTag.getCompound(j);
                    int x = blockTag.getInt("x");
                    int y = blockTag.getInt("y");
                    int z = blockTag.getInt("z");
                    int stateId = blockTag.getInt("stateId");

                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = Block.stateById(stateId);
                    CompoundTag nbt = blockTag.contains("nbt") ? blockTag.getCompound("nbt") : null;

                    // 旧快照防御：moving_piston 不带方块实体还原（活实体会自己 tick 完结、
                    // 掉落活塞物品）；能解读出「正在搬运的方块」就落位成它。
                    if (state.is(Blocks.MOVING_PISTON)) {
                        if (nbt != null && nbt.contains("blockState")) {
                            BlockState moved = net.minecraft.nbt.NbtUtils.readBlockState(
                                    level.registryAccess().lookupOrThrow(
                                            net.minecraft.core.registries.Registries.BLOCK),
                                    nbt.getCompound("blockState"));
                            if (!moved.isAir()) {
                                state = moved;
                            }
                        }
                        nbt = null;
                    }

                    level.setBlock(pos, state, MTRBlockFlags.SILENT_SET_BLOCK, MTRBlockFlags.SILENT_UPDATE_LIMIT);

                    // setBlock 会给 moving_piston 自动创建一个空的方块实体；
                    // 世界未冻结时它会自行 tick（更新邻居/掉物品）——杀掉。
                    if (state.is(Blocks.MOVING_PISTON)) {
                        level.removeBlockEntity(pos);
                    }

                    if (nbt != null) {
                        BlockEntity be = BlockEntity.loadStatic(pos, state, nbt, level.registryAccess());
                        if (be != null) {
                            level.setBlockEntity(be);
                        }
                    }
                }

                // Restore the entities
                ListTag entitiesTag = areaTag.getList("entities", Tag.TAG_COMPOUND);
                for (int j = 0; j < entitiesTag.size(); j++) {
                    CompoundTag entityNbt = entitiesTag.getCompound(j);
                    if (entityNbt.isEmpty()) continue;

                    if (asStandIns) {
                        EntityReplayManager.spawnStandInFromNbt(level, entityNbt);
                    } else {
                        EntityType.create(entityNbt, level).ifPresent(level::addFreshEntity);
                    }
                }
            }
        } catch (IOException e) {
            MicroTimingReplay.LOGGER.error("Failed to restore world for profile: {}", profile.getName(), e);
        }
    }

    public static void deleteBackups(String profileName) {
        File recordBackup = WorldScopedStorage.getBackupFile(profileName, "record");
        if (recordBackup.exists()) recordBackup.delete();
        File replayBackup = WorldScopedStorage.getBackupFile(profileName, "replay");
        if (replayBackup.exists()) replayBackup.delete();
    }
}
