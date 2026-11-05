package dev.mapartfeeder.runtime;

import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.TrappedChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** 原料容器判定与双箱合并。 */
public final class ContainerBlocks {
    private ContainerBlocks() { }

    public static boolean isContainer(BlockState state) {
        return state.getBlock() instanceof ChestBlock
                || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof ShulkerBoxBlock;
    }

    /** 原版方块容器在右键时总会消费这次交互，因此主手拿着东西也能开箱，不必换槽。 */
    public static boolean alwaysConsumesUse(BlockState state) {
        Class<?> type = state.getBlock().getClass();
        return type == ChestBlock.class || type == TrappedChestBlock.class
                || type == BarrelBlock.class || type == ShulkerBoxBlock.class;
    }

    /** 双箱两半映射到同一坐标（取较小的一半），避免同一容器被登记/缓存两次。 */
    public static BlockPos canonical(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.offset(ChestBlock.getFacing(state));
            if (world.getBlockState(other).getBlock() == state.getBlock()) {
                return pos.asLong() <= other.asLong() ? pos.toImmutable() : other.toImmutable();
            }
        }
        return pos.toImmutable();
    }
}
