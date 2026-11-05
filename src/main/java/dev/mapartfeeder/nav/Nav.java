package dev.mapartfeeder.nav;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 站立方块上的 A* 寻路与射线可见性判断。只读取世界，不修改世界。
 * 规则与玩家步行一致：水平四方向、上台阶 1 格、下落最多 3 格、不游泳、不飞行。
 */
public final class Nav {
    private static final int MAX_PATH_NODES = 6_000;
    private static final long PATH_TIME_BUDGET_NS = 25_000_000L;
    private static final double LOW_OBSTACLE_HEIGHT = 0.6;
    private static final double EYE_HEIGHT = 1.62;

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
     * 返回从当前位置出发要走的路点（不含起点）；已经到达返回空列表；
     * 完全找不到路返回 null。
     */
    public static List<BlockPos> findRoute(MinecraftClient client, Goal goal) {
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
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::f));
        best.put(start, 0.0);
        open.add(new PathNode(start, 0.0, goal.heuristic(start)));

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
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
            for (int[] direction : directions) {
                int x = node.pos().getX() + direction[0];
                int z = node.pos().getZ() + direction[1];
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                BlockPos level = new BlockPos(x, node.pos().getY(), z);
                BlockPos next = null;
                double stepCost = 0.0;
                if (canStand(world, level)) {
                    next = level;
                    stepCost = 1.0;
                } else if (canStand(world, level.up())) {
                    next = level.up();
                    stepCost = 1.4;
                } else {
                    for (int drop = 1; drop <= 3; drop++) {
                        BlockPos lower = level.down(drop);
                        if (canStand(world, lower)) {
                            next = lower;
                            stepCost = 1.0 + 0.15 * drop;
                            break;
                        }
                    }
                }
                if (next == null) continue;
                double cost = node.g() + stepCost;
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
        return new ArrayList<>(reversed);
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

    private record PathNode(BlockPos pos, double g, double f) { }
}
