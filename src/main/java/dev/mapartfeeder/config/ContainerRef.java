package dev.mapartfeeder.config;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 一个被登记过的方块位置（原料容器或地图画中心）。
 * 维度用 {@code RegistryKey<World>} 的值字符串保存，跨存档也稳定。
 */
public final class ContainerRef {
    public String dimension = "";
    public int x;
    public int y;
    public int z;

    public ContainerRef() { }

    public ContainerRef(String dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static ContainerRef of(RegistryKey<World> dimension, BlockPos pos) {
        return new ContainerRef(dimension.getValue().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    public boolean matches(RegistryKey<World> key) {
        return dimension.equals(key.getValue().toString())
                && x == pos().getX() && y == pos().getY() && z == pos().getZ();
    }

    public boolean sameDimension(RegistryKey<World> key) {
        return dimension.equals(key.getValue().toString());
    }

    /** 双箱两半共用同一个缓存键：取两者中坐标较小的一半。 */
    public String cacheKey() {
        return dimension + "|" + x + "," + y + "," + z;
    }

    public String shortText() {
        return x + ", " + y + ", " + z;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ContainerRef ref)) return false;
        return x == ref.x && y == ref.y && z == ref.z && dimension.equals(ref.dimension);
    }

    @Override
    public int hashCode() {
        return cacheKey().hashCode();
    }
}
