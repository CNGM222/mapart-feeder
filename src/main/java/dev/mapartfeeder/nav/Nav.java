package dev.mapartfeeder.nav;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 站立方块上的 A* 寻路与射线可见性判断。只读取世界，不修改世界。
 * 规则与玩家步行一致：水平八方向、上台阶 1 格、下落最多 3 格、不游泳、不飞行、不贴墙角斜切。
 *
 * <p>输出的不是 A* 原始的格子路径，而是拉直并重新采样后的折线：
 * 先对格子路径做视线拉直（string pulling），只保留必要的拐点，
 * 再按 {@link #POINT_SPACING} 均匀插值成密集路点，供 {@link Walker} 做前视点追踪。
 * 这样走出来的轨迹是一条条长直线，拐角由走位的转向限制自然磨成圆弧，而不是一格一折的锯齿。</p>
 */
public final class Nav {
    private static final int MAX_PATH_NODES = 6_000;
    private static final long PATH_TIME_BUDGET_NS = 25_000_000L;
    private static final long SMOOTH_TIME_BUDGET_NS = 8_000_000L;
    private static final double LOW_OBSTACLE_HEIGHT = 0.6;

    /** 拉直时每条直线最多跨多少个格子，太长会拖慢可见性检查。 */
    private static final int SMOOTH_RANGE = 24;
    /** 视线检查的采样间距（格）。 */
    private static final double LINE_SAMPLE = 0.25;
    /** 最终输出的路点间距（格）。 */
    private static final double POINT_SPACING = 0.45;
    private static final double DIAGONAL_COST = Math.sqrt(2.0);
    private static final int NO_STAND = Integer.MIN_VALUE;

    /** 八方向移动；后四个是对角。 */
    private static final int[][] DIRECTIONS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private Nav() { }

    /** 寻路目标：判断某个站立点是否已经满足需要。 */
    public interface Goal {
        boolean reached(BlockPos feet);
        double heuristic(BlockPos from);
    }

    /** 走到某个水平坐标附近，并允许 Y 在一个区间内浮动。 */
    public record PointGoal(int x, int z, int minY, int maxY, double radius) implements Goal {
        @Override
        public boolean reached(BlockPos feet) {
            double dx = feet.getX() + 0.5 - (x + 0.5);
            double dz = feet.getZ() + 0.5 - (z + 0.5);
            return dx * dx + dz * dz <= radius * radius
                    && feet.getY() >= minY && feet.getY() <= maxY;
        }

        @Override
        public double heuristic(BlockPos from) {
            return Math.hypot(from.getX() - x, from.getZ() - z);
        }
    }

    /**
     * 以容器为目标：站到能真正用视线点到容器方块的位置。
     * 这里用“虚拟眼睛”做射线，避免出现“离得近但被墙挡住”的假到达。
     */
    public record ContainerGoal(BlockPos container) implements Goal {
        @Override
        public boolean reached(BlockPos feet) {
            return true;
        }

        @Override
        public double heuristic(BlockPos from) {
            return Math.hypot(from.getX() - container.getX(), from.getZ() - container.getZ());
        }
    }

    public static double reach(MinecraftClient client) {
        return Math.max(1.0, client.player.getBlockInteractionRange() - 0.05);
    }

    public static boolean canStand(World world, BlockPos feet) {
        if (!world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        BlockState floorState = world.getBlockState(feet.down());
        if (!feetState.getFluidState().isEmpty()) return false;
        if (!headState.getFluidState().isEmpty()) return false;
        if (!floorState.getFluidState().isEmpty()) return false;
        if (!isLowObstacle(world, feet, feetState)) return false;
        if (!headState.getCollisionShape(world, feet.up()).isEmpty()) return false;
        return !floorState.getCollisionShape(world, feet.down()).isEmpty();
    }

    private static boolean isLowObstacle(World world, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return true;
        return shape.getBoundingBox().maxY <= LOW_OBSTACLE_HEIGHT;
    }

    public static BlockPos findNearestStand(World world, BlockPos origin, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dy = 1; dy >= -3; dy--) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos candidate = origin.add(dx, dy, dz);
                        if (canStand(world, candidate)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    public static Vec3d eyeAt(PlayerEntity player, BlockPos feet) {
        if (feet.equals(player.getBlockPos())) return player.getEyePos();
        return new Vec3d(feet.getX() + 0.5,
                feet.getY() + player.getEyeHeight(player.getPose()),
                feet.getZ() + 0.5);
    }

    /** 从当前视角判断容器是否能被点到（用于开箱前最后确认）。 */
    public static BlockHitResult hitContainer(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.world == null || !client.world.isChunkLoaded(pos)) return null;
        return hitContainerFrom(client.world, client.player, client.player.getEyePos(), pos, reach(client));
    }

    public static BlockHitResult hitContainerFrom(World world, PlayerEntity player, Vec3d eye,
                                                  BlockPos pos, double reach) {
        if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > (reach + 1.0) * (reach + 1.0)) return null;
        double reachSq = reach * reach;
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d[] points = {center,
                center.add(0.40, 0, 0), center.add(-0.40, 0, 0),
                center.add(0, 0.40, 0), center.add(0, -0.40, 0),
                center.add(0, 0, 0.40), center.add(0, 0, -0.40)};
        for (Vec3d point : points) {
            BlockHitResult hit = world.raycast(new RaycastContext(eye, point,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                    && eye.squaredDistanceTo(hit.getPos()) <= reachSq) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 返回从当前位置出发要走的路点（不含起点方块中心）；已经到达返回空列表；
     * 完全找不到路返回 null。返回的是拉直、等距重采样后的折线，Y 是脚所在方块的高度。
     */
    public static List<Vec3d> findRoute(MinecraftClient client, Goal goal) {
        World world = client.world;
        PlayerEntity player = client.player;
        BlockPos start = player.getBlockPos().toImmutable();
        if (!canStand(world, start)) {
            BlockPos adjusted = findNearestStand(world, start, 3);
            if (adjusted == null) return null;
            start = adjusted;
        }
        if (arrived(client, start, goal)) return new ArrayList<>();

        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Long2ByteOpenHashMap stands = new Long2ByteOpenHashMap();
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::f));
        best.put(start, 0.0);
        open.add(new PathNode(start, 0.0, goal.heuristic(start)));

        long deadline = System.nanoTime() + PATH_TIME_BUDGET_NS;
        BlockPos found = null;
        int expanded = 0;

        while (!open.isEmpty() && expanded < MAX_PATH_NODES) {
            if ((++expanded & 31) == 0 && System.nanoTime() > deadline) break;
            PathNode node = open.poll();
            Double current = best.get(node.pos());
            if (current != null && node.g() > current + 1.0E-6) continue;
            if (arrived(client, node.pos(), goal)) {
                found = node.pos();
                break;
            }
            for (int[] direction : DIRECTIONS) {
                Step step = step(world, node.pos(), direction[0], direction[1], stands);
                if (step == null) continue;
                BlockPos next = step.pos();
                double cost = node.g() + step.cost();
                Double known = best.get(next);
                if (known != null && cost >= known - 1.0E-6) continue;
                best.put(next, cost);
                parent.put(next, node.pos());
                open.add(new PathNode(next, cost, cost + goal.heuristic(next)));
            }
        }

        if (found == null) return null;
        ArrayDeque<BlockPos> reversed = new ArrayDeque<>();
        for (BlockPos pos = found; !pos.equals(start); pos = parent.get(pos)) {
            reversed.addFirst(pos);
            if (reversed.size() > MAX_PATH_NODES) break;
        }
        List<BlockPos> nodes = new ArrayList<>(reversed.size() + 1);
        nodes.add(start);
        nodes.addAll(reversed);
        return smooth(world, start, nodes, stands);
    }

    /** 从 from 走到相邻格 (dx,dz) 的结果；走不过去返回 null。对角移动不允许贴墙角。 */
    private static Step step(World world, BlockPos from, int dx, int dz, Long2ByteOpenHashMap stands) {
        int x = from.getX() + dx;
        int z = from.getZ() + dz;
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
        boolean diagonal = dx != 0 && dz != 0;
        if (diagonal && (step(world, from, dx, 0, stands) == null
                || step(world, from, 0, dz, stands) == null)) {
            return null;
        }
        BlockPos level = new BlockPos(x, from.getY(), z);
        double factor = diagonal ? DIAGONAL_COST : 1.0;
        if (stand(world, level, stands)) return new Step(level, factor);
        BlockPos up = level.up();
        if (stand(world, up, stands)) return new Step(up, 1.4 * factor);
        for (int drop = 1; drop <= 3; drop++) {
            BlockPos lower = level.down(drop);
            if (stand(world, lower, stands)) return new Step(lower, (1.0 + 0.15 * drop) * factor);
        }
        return null;
    }

    /** 本次寻路内缓存可站立判定：同一个格子会被相邻的多次扩展重复检查。 */
    private static boolean stand(World world, BlockPos pos, Long2ByteOpenHashMap stands) {
        long key = key(pos.getX(), pos.getY(), pos.getZ());
        byte known = stands.get(key);
        if (known != 0) return known == 1;
        boolean value = canStand(world, pos);
        if (stands.size() < 200_000) stands.put(key, (byte) (value ? 1 : 2));
        return value;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /**
     * 两点之间能否按玩家的走法直着过去：按 {@link #LINE_SAMPLE} 采样，
     * 允许上台阶 1 格、下落 3 格；横向跨格时两侧也要能站，避免从两个墙角的缝隙里斜穿。
     */
    static boolean walkableLine(World world, BlockPos from, BlockPos to) {
        return walkableLine(world, from, to, new Long2ByteOpenHashMap());
    }

    private static boolean walkableLine(World world, BlockPos from, BlockPos to,
                                        Long2ByteOpenHashMap stands) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 0.001) return Math.abs(to.getY() - from.getY()) <= 1;
        int steps = (int) Math.ceil(horizontal / LINE_SAMPLE);
        int y = from.getY();
        int cellX = from.getX();
        int cellZ = from.getZ();
        for (int index = 1; index <= steps; index++) {
            double t = index / (double) steps;
            int nextX = (int) Math.floor(from.getX() + 0.5 + (to.getX() - from.getX()) * t);
            int nextZ = (int) Math.floor(from.getZ() + 0.5 + (to.getZ() - from.getZ()) * t);
            if (!world.isChunkLoaded(nextX >> 4, nextZ >> 4)) return false;
            if (nextX != cellX && nextZ != cellZ) {
                if (!passableSide(world, nextX, cellZ, y, stands)) return false;
                if (!passableSide(world, cellX, nextZ, y, stands)) return false;
            }
            int found = standableY(world, nextX, nextZ, y, stands);
            if (found == NO_STAND || found - y > 1) return false;
            y = found;
            cellX = nextX;
            cellZ = nextZ;
        }
        return Math.abs(y - to.getY()) <= 1;
    }

    private static boolean passableSide(World world, int x, int z, int y, Long2ByteOpenHashMap stands) {
        int found = standableY(world, x, z, y, stands);
        return found != NO_STAND && found - y <= 1;
    }

    /** 从 preferY 开始找一个能站的高度：原地、上一格、再往下 1~3 格。 */
    private static int standableY(World world, int x, int z, int preferY, Long2ByteOpenHashMap stands) {
        BlockPos pos = new BlockPos(x, preferY, z);
        if (stand(world, pos, stands)) return preferY;
        BlockPos up = pos.up();
        if (stand(world, up, stands)) return preferY + 1;
        for (int drop = 1; drop <= 3; drop++) {
            if (stand(world, pos.down(drop), stands)) return preferY - drop;
        }
        return NO_STAND;
    }

    /**
     * 视线拉直：从当前拐点尽量往后找最远能直着走到的格子，只保留这些拐点。
     * 拉直有 8ms 的时间预算，超时就原样接上剩余格子，保证不会卡住游戏线程。
     */
    private static List<Vec3d> smooth(World world, BlockPos start, List<BlockPos> nodes,
                                      Long2ByteOpenHashMap stands) {
        List<BlockPos> corners = new ArrayList<>();
        corners.add(nodes.get(0));
        int index = 0;
        long deadline = System.nanoTime() + SMOOTH_TIME_BUDGET_NS;
        while (index < nodes.size() - 1) {
            int limit = Math.min(nodes.size() - 1, index + SMOOTH_RANGE);
            int chosen = index + 1;
            for (int candidate = limit; candidate > index + 1; candidate--) {
                if (walkableLine(world, nodes.get(index), nodes.get(candidate), stands)) {
                    chosen = candidate;
                    break;
                }
            }
            corners.add(nodes.get(chosen));
            index = chosen;
            if (System.nanoTime() > deadline) {
                for (int rest = index + 1; rest < nodes.size(); rest++) corners.add(nodes.get(rest));
                break;
            }
        }
        return densify(start, corners);
    }

    /** 把拐点折线按等距采样成密集路点，供前视点追踪；Y 随台阶线性过渡。 */
    private static List<Vec3d> densify(BlockPos start, List<BlockPos> corners) {
        List<Vec3d> points = new ArrayList<>();
        Vec3d previous = new Vec3d(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        points.add(previous);
        for (BlockPos corner : corners) {
            Vec3d target = new Vec3d(corner.getX() + 0.5, corner.getY(), corner.getZ() + 0.5);
            double distance = Math.hypot(target.x - previous.x, target.z - previous.z);
            if (distance < 1.0E-4) {
                if (target.y != previous.y) {
                    points.add(target);
                    previous = target;
                }
                continue;
            }
            int steps = Math.max(1, (int) Math.ceil(distance / POINT_SPACING));
            for (int step = 1; step <= steps; step++) {
                double t = step / (double) steps;
                points.add(new Vec3d(
                        previous.x + (target.x - previous.x) * t,
                        previous.y + (target.y - previous.y) * t,
                        previous.z + (target.z - previous.z) * t));
            }
            previous = target;
        }
        return points;
    }

    /** 当前位置是否已经满足目标：容器目标会做真实射线检查。 */
    public static boolean isAtGoal(MinecraftClient client, Goal goal) {
        return client.player != null && arrived(client, client.player.getBlockPos(), goal);
    }

    private static boolean arrived(MinecraftClient client, BlockPos feet, Goal goal) {
        if (goal instanceof ContainerGoal container) {
            Vec3d eye = eyeAt(client.player, feet);
            return hitContainerFrom(client.world, client.player, eye, container.container(), reach(client)) != null;
        }
        return goal.reached(feet);
    }

    private record Step(BlockPos pos, double cost) { }

    private record PathNode(BlockPos pos, double g, double f) { }
}
