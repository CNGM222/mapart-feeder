package dev.mapartfeeder.nav;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 按键级走位：沿 {@link Nav} 给出的密集路点做前视点追踪（pure pursuit）。
 *
 * <p>每 tick 只看向“前方一个前视距离处的路点”，并按每 tick 的最大转角逐步转向，
 * 所以拐角会被磨成圆弧，不会出现一格一折的锯齿；同时保留按键所有权与卡住自救。</p>
 *
 * <p>拥有前进/跳跃键时保存原状态，结束或被打断时原样恢复。</p>
 */
public final class Walker {
    public enum Result { RUNNING, ARRIVED, FAILED }

    /** 前视距离：看向路径上约这么远的路点，越大拐角越圆、越容易切角。 */
    private static final double LOOKAHEAD = 1.15;
    /** 认为已经走过某个路点的水平距离。 */
    private static final double PASS_RADIUS = 0.4;
    private static final double ARRIVE_RADIUS = 0.55;
    private static final int REPATH_INTERVAL_TICKS = 120;
    private static final int STALL_TICKS = 50;
    private static final int MAX_PATH_FAILURES = 3;
    private static final int MAX_STALL_REPATHS = 2;
    /** 普通转向每 tick 的最大角度；差角太大时先用大角度快速对准，避免长时间跑偏。 */
    private static final float TURN_STEP = 16.0f;
    private static final float TURN_STEP_LARGE = 30.0f;
    private static final float LARGE_TURN = 60.0f;

    private final List<Vec3d> path = new ArrayList<>();
    private Nav.Goal lastGoal;
    private int passIndex;
    private int routeAge;
    private int pathFailures;
    private int stallRepaths;
    private int idleTicks;
    private int stalledTicks;
    private int forceJump;
    private int stallJumps;
    private int routeMisses;
    private int noProgressTicks;
    private Vec3d lastPosition;

    private boolean movementOwned;
    private boolean smoothTurning = true;
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
            routeMisses = 0;
            release(client);
            resetRoute();
            return Result.ARRIVED;
        }
        // 目标换了就必须丢掉旧路线，否则会继续朝上一个目标走一段时间，看起来像来回折返。
        if (lastGoal == null || !lastGoal.equals(goal)) {
            lastGoal = goal;
            routeMisses = 0;
            resetRoute();
        }
        if (path.isEmpty() || routeAge++ > REPATH_INTERVAL_TICKS || needsRepath(client)) {
            if (!approach(client, goal)) {
                release(client);
                idleTicks++;
                if (pathFailures >= MAX_PATH_FAILURES || idleTicks > 20) {
                    return Result.FAILED;
                }
                return Result.RUNNING;
            }
        }
        return follow(client, goal);
    }

    private boolean approach(MinecraftClient client, Nav.Goal goal) {
        List<Vec3d> route = Nav.findRoute(client, goal);
        routeAge = 0;
        if (route == null || route.isEmpty()) {
            path.clear();
            pathFailures++;
            return false;
        }
        pathFailures = 0;
        idleTicks = 0;
        stallRepaths = 0;
        stalledTicks = 0;
        stallJumps = 0;
        noProgressTicks = 0;
        lastPosition = null;
        path.clear();
        path.addAll(route);
        // 路径的第一个点是出发格中心，玩家可能已经站在格子边缘；
        // 直接锚到离玩家最近的路点，避免为了“走回起点”而判成卡住。
        passIndex = nearestIndex(client.player.getEntityPos());
        return true;
    }

    private int nearestIndex(Vec3d feet) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            double distance = horizontalSq(feet, path.get(i));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** 玩家被挤开或偏离路线太远时重新规划。 */
    private boolean needsRepath(MinecraftClient client) {
        if (path.isEmpty()) return true;
        Vec3d feet = client.player.getEntityPos();
        Vec3d point = path.get(Math.min(passIndex, path.size() - 1));
        return horizontalSq(feet, point) > 9.0;
    }

    private Result follow(MinecraftClient client, Nav.Goal goal) {
        Vec3d feet = client.player.getEntityPos();
        if (advancePass(feet)) {
            stalledTicks = 0;
        } else {
            stalledTicks++;
        }

        Vec3d last = path.get(path.size() - 1);
        if (passIndex >= path.size() - 1 && horizontalSq(feet, last) < ARRIVE_RADIUS * ARRIVE_RADIUS) {
            if (Nav.isAtGoal(client, goal)) {
                release(client);
                resetRoute();
                return Result.ARRIVED;
            }
            // 路走完了却没满足目标：偶尔重走一次，连续几次都这样说明这个目标到不了。
            if (++routeMisses >= 4) {
                release(client);
                return Result.FAILED;
            }
            release(client);
            resetRoute();
            return Result.RUNNING;
        }

        int aim = chooseAim(client, feet);
        Vec3d target = path.get(aim);
        double dx = target.x - feet.x;
        double dz = target.z - feet.z;
        if (dx * dx + dz * dz < 0.04 && aim < path.size() - 1) {
            aim++;
            target = path.get(aim);
            dx = target.x - feet.x;
            dz = target.z - feet.z;
        }

        // 逐步转向目标方向：每 tick 只转有限角度，实际轨迹在拐角处走圆弧。
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float currentYaw = client.player.getYaw();
        float delta = MathHelper.wrapDegrees(desiredYaw - currentYaw);
        float maxStep = smoothTurning
                ? (Math.abs(delta) > LARGE_TURN ? TURN_STEP_LARGE : TURN_STEP)
                : 180.0f;
        client.player.setYaw(MathHelper.wrapDegrees(
                currentYaw + MathHelper.clamp(delta, -maxStep, maxStep)));
        client.player.setPitch(0.0f);
        client.player.setSprinting(false);

        ownKeys(client);
        client.options.forwardKey.setPressed(true);

        // 前方需要上台阶时在贴近方块的那一刻起跳；太远的台阶跳了也没用。
        boolean stepAhead = false;
        double nearestStep = Double.MAX_VALUE;
        int limit = Math.min(path.size() - 1, passIndex + 12);
        for (int i = passIndex; i <= limit; i++) {
            Vec3d point = path.get(i);
            if (point.y <= feet.y + 0.55) continue;
            stepAhead = true;
            nearestStep = Math.min(nearestStep, Math.sqrt(horizontalSq(feet, point)));
        }
        boolean wantJump = forceJump > 0
                || (stepAhead && nearestStep < 1.3
                && (client.player.horizontalCollision || nearestStep < 0.6));
        client.options.jumpKey.setPressed(wantJump && client.player.isOnGround());
        if (forceJump > 0) forceJump--;

        // 连续两秒半几乎没有位移就放弃这个目标，避免一直原地起跳、转圈。
        if (lastPosition != null && horizontalSq(feet, lastPosition) < 0.0025) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
        }
        lastPosition = feet;
        if (noProgressTicks > 50) {
            release(client);
            return Result.FAILED;
        }

        if (stalledTicks > STALL_TICKS) {
            stalledTicks = 0;
            // 只有台阶就在眼前时才用起跳自救；平地上顶着墙跳没有意义。
            if (stepAhead && nearestStep < 1.3 && stallJumps < 2) {
                stallJumps++;
                forceJump = 10;
                return Result.RUNNING;
            }
            if (++stallRepaths > MAX_STALL_REPATHS) {
                release(client);
                return Result.FAILED;
            }
            path.clear();
            routeAge = REPATH_INTERVAL_TICKS;
            noProgressTicks = 0;
        }
        return Result.RUNNING;
    }

    /** 推进“已走过”的路点；拐弯时被切掉的路点也会跳过，避免误判卡住。 */
    private boolean advancePass(Vec3d feet) {
        int before = passIndex;
        int limit = Math.min(path.size() - 1, passIndex + 4);
        int nearest = passIndex;
        double nearestDistance = horizontalSq(feet, path.get(passIndex));
        for (int i = passIndex + 1; i <= limit; i++) {
            double distance = horizontalSq(feet, path.get(i));
            if (distance < nearestDistance - 1.0E-6) {
                nearestDistance = distance;
                nearest = i;
            }
        }
        if (nearest > passIndex && nearestDistance <= 0.49) {
            passIndex = nearest;
        }
        while (passIndex < path.size() - 1) {
            if (horizontalSq(feet, path.get(passIndex)) > PASS_RADIUS * PASS_RADIUS) break;
            passIndex++;
        }
        return passIndex != before;
    }

    /**
     * 选前视点：从已走过的路点往前找到第一个超过前视距离的点。
     * 如果从这个点直线过去会穿墙，就退回最近的、能直着走到的点，避免切角切进方块。
     */
    private int chooseAim(MinecraftClient client, Vec3d feet) {
        int aim = passIndex;
        while (aim < path.size() - 1 && horizontalDistance(feet, path.get(aim)) < LOOKAHEAD) aim++;
        BlockPos from = client.player.getBlockPos();
        for (int attempt = 0; attempt < 4 && aim > passIndex; attempt++) {
            Vec3d point = path.get(aim);
            if (Nav.walkableLine(client.world, from, BlockPos.ofFloored(point.x, point.y, point.z))) break;
            aim--;
        }
        if (aim == passIndex) aim = Math.min(path.size() - 1, passIndex + 1);
        return aim;
    }

    /** 平滑转向开关：关掉后每 tick 直接对准目标方向，仅作为狭窄地形的兜底。 */
    public void setSmoothTurning(boolean value) {
        smoothTurning = value;
    }

    public void resetRoute() {
        path.clear();
        passIndex = 0;
        routeAge = 0;
        pathFailures = 0;
        stallRepaths = 0;
        idleTicks = 0;
        stalledTicks = 0;
        stallJumps = 0;
        noProgressTicks = 0;
        lastPosition = null;
        forceJump = 0;
    }

    /** 放弃当前目标并恢复玩家按键状态。 */
    public void abort(MinecraftClient client) {
        resetRoute();
        release(client);
    }

    private void ownKeys(MinecraftClient client) {
        if (movementOwned) return;
        savedForward = client.options.forwardKey.isPressed();
        savedJump = client.options.jumpKey.isPressed();
        savedSneak = client.options.sneakKey.isPressed();
        savedSprint = client.options.sprintKey.isPressed();
        savedLeft = client.options.leftKey.isPressed();
        savedRight = client.options.rightKey.isPressed();
        movementOwned = true;
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
    }

    private static double horizontalSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static double horizontalDistance(Vec3d a, Vec3d b) {
        return Math.sqrt(horizontalSq(a, b));
    }
}
