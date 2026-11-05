package dev.mapartfeeder.nav;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 按键级走位：拥有前进/跳跃键时保存原状态，结束或被打断时原样恢复。
 * 卡住会先尝试起跳、再横向蹭一下，两次都没进展就放弃该目标。
 */
public final class Walker {
    public enum Result { RUNNING, ARRIVED, FAILED }

    private static final int REPATH_INTERVAL_TICKS = 40;
    private static final int STALL_TICKS = 50;
    private static final int MAX_PATH_FAILURES = 3;
    private static final int MAX_STALL_REPATHS = 2;

    private final Deque<BlockPos> route = new ArrayDeque<>();
    private int routeAge;
    private int pathFailures;
    private int stallRepaths;
    private int idleTicks;

    private BlockPos trackedWaypoint;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private int stalledTicks;
    private int forceJump;
    private int nudgeTicks;
    private BlockPos nudgeDone;

    private boolean movementOwned;
    private boolean savedForward;
    private boolean savedJump;
    private boolean savedSneak;
    private boolean savedSprint;
    private boolean savedLeft;
    private boolean savedRight;

    public Result tick(MinecraftClient client, Nav.Goal goal) {
        if (client.player == null || client.world == null) {
            release(client);
            return Result.FAILED;
        }
        if (Nav.isAtGoal(client, goal)) {
            release(client);
            resetRoute();
            return Result.ARRIVED;
        }
        if (route.isEmpty() || routeAge++ > REPATH_INTERVAL_TICKS) {
            approach(client, goal);
            if (route.isEmpty()) {
                if (idleTicks++ > 20) {
                    release(client);
                    return Result.FAILED;
                }
                return Result.RUNNING;
            }
        }
        return follow(client, goal);
    }

    private void approach(MinecraftClient client, Nav.Goal goal) {
        List<BlockPos> path = Nav.findRoute(client, goal);
        routeAge = 0;
        if (path == null) {
            route.clear();
            if (++pathFailures >= MAX_PATH_FAILURES) {
                return;
            }
            return;
        }
        pathFailures = 0;
        idleTicks = 0;
        route.clear();
        route.addAll(path);
        resetProgress();
    }

    private Result follow(MinecraftClient client, Nav.Goal goal) {
        BlockPos waypoint = route.peekFirst();
        Vec3d feet = client.player.getEntityPos();
        double dx = waypoint.getX() + 0.5 - feet.x;
        double dz = waypoint.getZ() + 0.5 - feet.z;
        double dy = waypoint.getY() - feet.y;
        if (dx * dx + dz * dz < 0.16 && Math.abs(dy) < 0.5) {
            route.removeFirst();
            resetProgress();
            if (route.isEmpty()) {
                release(client);
                if (Nav.isAtGoal(client, goal)) return Result.ARRIVED;
                routeAge = REPATH_INTERVAL_TICKS;
            }
            return Result.RUNNING;
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        client.player.setYaw(MathHelper.wrapDegrees(yaw));
        client.player.setPitch(0.0f);
        client.player.setSprinting(false);

        if (!movementOwned) {
            savedForward = client.options.forwardKey.isPressed();
            savedJump = client.options.jumpKey.isPressed();
            savedSneak = client.options.sneakKey.isPressed();
            savedSprint = client.options.sprintKey.isPressed();
            savedLeft = client.options.leftKey.isPressed();
            savedRight = client.options.rightKey.isPressed();
            movementOwned = true;
        }
        client.options.forwardKey.setPressed(true);
        boolean closeEnough = dx * dx + dz * dz < 4.0;
        boolean wantJump = (dy > 0.5 && closeEnough) || forceJump > 0;
        client.options.jumpKey.setPressed(wantJump && client.player.isOnGround());
        if (forceJump > 0) forceJump--;
        if (nudgeTicks > 0) {
            nudgeTicks--;
            boolean left = (nudgeTicks / 5) % 2 == 0;
            client.options.leftKey.setPressed(left);
            client.options.rightKey.setPressed(!left);
        } else {
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }

        double distance = Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 1.5;
        if (!waypoint.equals(trackedWaypoint)) {
            trackedWaypoint = waypoint;
            lastDistance = distance;
            stalledTicks = 0;
        } else if (distance < lastDistance - 0.02) {
            lastDistance = distance;
            stalledTicks = 0;
        } else if (++stalledTicks > STALL_TICKS) {
            stalledTicks = 0;
            lastDistance = Double.POSITIVE_INFINITY;
            trackedWaypoint = null;
            forceJump = 12;
            if (nudgeTicks <= 0 && !waypoint.equals(nudgeDone)) {
                nudgeDone = waypoint;
                nudgeTicks = 16;
                return Result.RUNNING;
            }
            if (++stallRepaths > MAX_STALL_REPATHS) {
                release(client);
                return Result.FAILED;
            }
            route.clear();
            routeAge = REPATH_INTERVAL_TICKS;
        }
        return Result.RUNNING;
    }

    public void resetRoute() {
        route.clear();
        routeAge = 0;
        pathFailures = 0;
        stallRepaths = 0;
        idleTicks = 0;
        resetProgress();
    }

    private void resetProgress() {
        trackedWaypoint = null;
        lastDistance = Double.POSITIVE_INFINITY;
        stalledTicks = 0;
        nudgeTicks = 0;
        nudgeDone = null;
    }

    /** 放弃当前目标并恢复玩家按键状态。 */
    public void abort(MinecraftClient client) {
        route.clear();
        resetRoute();
        release(client);
    }

    private void release(MinecraftClient client) {
        if (!movementOwned) return;
        movementOwned = false;
        client.options.forwardKey.setPressed(savedForward);
        client.options.jumpKey.setPressed(savedJump);
        client.options.sneakKey.setPressed(savedSneak);
        client.options.sprintKey.setPressed(savedSprint);
        client.options.leftKey.setPressed(savedLeft);
        client.options.rightKey.setPressed(savedRight);
        nudgeTicks = 0;
    }
}
