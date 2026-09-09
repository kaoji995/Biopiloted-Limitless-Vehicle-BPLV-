package cn.blockforge.generated.soldiervehiclelink.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 可选的 RVP 载具兼容层。
 *
 * <p>这里不把 RVP 或士兵模组写成硬依赖。单位仍使用自己的 Mob AI；
 * 本类只把单位 AI 产生的目标、朝向和移动意图翻译成 RVP 载具端口的输入。
 * 其他模组的原生载具不会被识别或接管。</p>
 */
public final class RvpVehicleBridge {
    private static final String RVP_VEHICLE_PREFIX = "org.ywzj.vehicle.entity.vehicle.";
    private static final String RVP_VEHICLE_PACKAGE = "org.ywzj.vehicle.";
    private static final double STOP_DISTANCE = 3.5D;
    private static final double FORWARD_OBSTACLE_LOOKAHEAD = 1.25D;
    private static final int STUCK_TICKS_BEFORE_REVERSE = 2;
    private static final int REVERSE_ESCAPE_TICKS = 30;
    private static final double REVERSE_ESCAPE_CLEARANCE = 0.35D;
    private static final double MIN_PROGRESS_SQR = 0.0004D;
    private static final double REAR_TARGET_DOT = -0.75D;
    private static final double STEP_CLEARANCE_HEIGHT = 1.0D;
    private static final double FREE_ROAM_MIN_RADIUS = 10.0D;
    private static final double FREE_ROAM_MAX_RADIUS = 28.0D;
    private static final double FREE_ROAM_REACHED_DISTANCE = 4.5D;
    private static final int FREE_ROAM_MAX_ATTEMPTS = 12;
    private static final double WEAPON_ALIGNMENT_TOLERANCE_DEGREES = 5.0D;
    private static final Map<Entity, Integer> REVERSE_ESCAPE_UNTIL = new WeakHashMap<>();
    private static final Map<Entity, Vec3> LAST_NAVIGATION_POSITION = new WeakHashMap<>();
    private static final Map<Entity, Integer> NAVIGATION_STUCK_TICKS = new WeakHashMap<>();
    private static final Map<Entity, Vec3> FREE_ROAM_TARGETS = new WeakHashMap<>();
    // 仅指令下“移动指令”会抑制士兵的战斗目标（若装有 Epic Fight 的追打 Goal）。
    // 这里在桥接层记住最近一次真实目标，移动时仍把目标喂给炮塔端口，保证“边走边打”。
    private static final Map<Entity, Entity> LAST_ENGAGED_TARGET = new WeakHashMap<>();
    private static final double TARGET_ENGAGE_RANGE_SQR = 100.0D * 100.0D;

    private RvpVehicleBridge() {
    }

    /** 任意有 AI 的生物都可作为端口操作者；玩家不做自动接管，避免抢走玩家的载具控制。 */
    public static boolean isControllableUnit(Entity entity) {
        return entity instanceof LivingEntity living
                && !(living instanceof net.minecraft.world.entity.player.Player)
                && !isVehicle(entity);
    }

    /** 只识别永无止境 RVP 载具，不接管其他模组的原生载具。 */
    public static boolean isVehicle(Entity entity) {
        if (entity == null) {
            return false;
        }
        String className = entity.getClass().getName();
        if (className.startsWith(RVP_VEHICLE_PREFIX)) {
            return hasMethod(entity, "getDriver")
                    || hasMethod(entity, "getOwnOperatorUnit")
                    || hasField(entity, "controlUnit");
        }
        Package entityPackage = entity.getClass().getPackage();
        String packageName = entityPackage == null ? "" : entityPackage.getName();
        return packageName.startsWith(RVP_VEHICLE_PACKAGE)
                && (hasMethod(entity, "getDriver") || hasMethod(entity, "getOwnOperatorUnit"));
    }

    /** 判断单位是否占据 RVP 的驾驶端口，而不只是普通乘客位。 */
    public static boolean isVehicleOperator(Entity unit, Entity vehicle) {
        if (!isControllableUnit(unit) || !isVehicle(vehicle) || vehicle == null) {
            return false;
        }
        Object driver = unwrapOptional(invokeFirst(vehicle, new String[]{"getDriver", "getControllingPassenger", "getPilot"}));
        if (driver == unit) {
            return true;
        }

        // getOwnOperatorUnit 对炮手、观察员等所有操作位都可能返回非空，不能据此判定驾驶位。
        Object mappedSeat = findSeatForEntity(vehicle, unit);
        if (mappedSeat != null) {
            return isDriverRole(mappedSeat);
        }
        Object operatorPort = getCrewPort(vehicle, unit);
        if (operatorPort != null) {
            return isDriverRole(operatorPort);
        }

        // 没有 RVP 端口 API 的老载具，沿用 Minecraft 第一乘员作为控制者。
        return !hasExplicitRvpPort(vehicle)
                && !vehicle.getPassengers().isEmpty()
                && vehicle.getPassengers().get(0) == unit;
    }

    /** 每 tick 只把驾驶单位的目标和 AI 移动意图写入车辆控制端口。 */
    public static void tickOperator(LivingEntity unit, Entity vehicle) {
        if (!isVehicleOperator(unit, vehicle)) {
            return;
        }

        Entity target = resolveUnitTarget(unit);
        if (target != null) {
            // 无论何种策略，都记录最近一次真实攻击目标，供“仅指令移动”抑制目标时沿用。
            rememberEngagedTarget(unit, target);
        }
        Object operatorPort = getCrewPort(vehicle, unit);
        CommandIntent command = resolveCommandIntent(unit);
        // 只清理本桥接上一 tick 写入的方向输入，不清空 RVP 自己维护的驾驶状态。
        clearMovementInput(vehicle);
        if (target != null && command.autonomousCombat()) {
            // “自主战斗”沿用士兵的边走边追：车体直接朝敌人推进，倒车脱困仍只由导航目标触发。
            applyTargetToVehicle(vehicle, operatorPort, target);
            driveControlPort(vehicle, target, false);
            fireWeaponPort(operatorPort, unit, target, vehicle, true);
            return;
        }

        // “仅指令”遇敌：车体不登记攻击目标，避免 RVP 原生 AI 自己追着敌人跑；
        // 敌人只挂到炮塔端口上，驾驶权始终留给玩家的移动指令。
        clearTargetFromVehicle(vehicle, operatorPort);
        if (target != null) {
            applyAttackPort(operatorPort, target);
        }

        // 百年战争的指挥点与巡逻当前路点优先于普通导航器目标。
        Object movementTarget = command.movementTarget() != null
                ? command.movementTarget()
                : resolveMovementTarget(unit, target == null);
        if (command.freeRoam()) {
            // 自由探索没有固定目的地：到达或卡住当前点后，重新抽取一个附近的随机探索点。
            movementTarget = resolveFreeRoamTarget(unit, vehicle, movementTarget);
        }
        // 仅指令下，玩家下达的移动指令会（在有 Epic Fight 追打 Goal 时）抑制士兵的
        // 战斗目标；士兵本体每 tick 都把 getHywTarget()/getTarget() 清成 null，导致
        // 桥接层误判为“无敌人”、炮塔回正。这里在移动时沿用最近一次真实目标，
        // 让炮塔保持锁定直到目标死亡或驶出交战范围，同时车体照常执行玩家的移动指令。
        if (target == null && movementTarget != null && !isExplicitCeaseFire(unit)) {
            Entity latched = lastEngagedTarget(unit, vehicle);
            if (latched != null) {
                target = latched;
                applyAttackPort(operatorPort, target);
            }
        }
        if (movementTarget != null) {
            sendMovementOrder(vehicle, movementTarget);
        } else {
            stopVehicle(vehicle);
            if (target == null) {
                forgetEngagedTarget(unit);
            }
        }

        if (target != null) {
            // “仅指令”边走边打：车体照常执行玩家指令移动，炮塔独立锁定敌人瞄准开火。
            fireWeaponPort(operatorPort, unit, target, vehicle, false);
            return;
        }

        // 没有敌人时不保留上一目标的炮塔姿态：先服从“设置朝向”，否则回正到车体正前方。
        // 车体转向指令是在本 tick 实体更新阶段才执行的，而这里处于 START 阶段，
        // 因此 vehicle.getYRot() 还是“上一 tick”的朝向。直接拿它当炮塔瞄准点会让炮塔
        // 慢半拍去追车身。这里改用本 tick 指令要去的航向，让炮塔超前于车身的转向，
        // 消除“车身先转过去、炮塔后过去”的迟滞感。
        Vec3 idleAim;
        if (command.facingTarget() != null) {
            idleAim = command.facingTarget();
        } else {
            float commandedYaw = vehicle.getYRot();
            Vec3 movePos = targetPosition(movementTarget);
            if (movePos != null) {
                commandedYaw = yawTowards(vehicle.position(), movePos);
            }
            idleAim = forwardAimPoint(vehicle, commandedYaw);
        }
        aimIdleWeaponPorts(vehicle, operatorPort, idleAim);
        if (movementTarget == null && command.facingTarget() != null) {
            turnControlPortToYaw(vehicle, yawTowards(vehicle.position(), command.facingTarget()));
        }
    }

    /** 炮手等非驾驶乘员只操作自己对应的瞄准和武器端口。 */
    public static void tickCrewMember(LivingEntity unit, Entity vehicle) {
        if (!isControllableUnit(unit) || !isVehicle(vehicle) || unit.getVehicle() != vehicle
                || isVehicleOperator(unit, vehicle)) {
            return;
        }
        Object operatorPort = getCrewPort(vehicle, unit);
        if (operatorPort == null) {
            return;
        }
        Entity target = resolveUnitTarget(unit);
        if (target == null) {
            clearOperatorPort(operatorPort);
            return;
        }
        applyAttackPort(operatorPort, target);
        fireWeaponPort(operatorPort, unit, target);
    }

    /** 标准骑乘关系建立后只识别真实驾驶位，不把普通乘员切换到驾驶位。 */
    public static boolean adoptMountedOperator(LivingEntity unit, Entity vehicle) {
        return isControllableUnit(unit) && isVehicle(vehicle) && unit.getVehicle() == vehicle
                && isVehicleOperator(unit, vehicle);
    }

    public static void releaseMountedOperator(LivingEntity unit, Entity vehicle) {
        if (unit == null || vehicle == null || !isVehicle(vehicle)) {
            return;
        }
        if (isVehicleOperator(unit, vehicle)) {
            clearMovementInput(vehicle);
            invokeFirst(vehicle, new String[]{"clearDriver", "releaseDriver"}, unit);
        } else {
            clearOperatorPort(getCrewPort(vehicle, unit));
        }
        forgetEngagedTarget(unit);
        clearRegisteredSeat(vehicle, unit);
    }

    private static Entity resolveUnitTarget(LivingEntity unit) {
        if (unit instanceof Mob mob && mob.getTarget() != null && mob.getTarget().isAlive()) {
            return mob.getTarget();
        }
        Object target = invokeFirstNonNull(unit, new String[]{
                // 士兵模组的 RTS 追击目标需要优先读取，否则地面点会被误判成普通攻击目标。
                "getHywTargetForPursuitMovement", "getHywTarget", "getAttackTarget", "getCombatTarget", "getTarget",
                "getTargetEntity", "getTrackedTarget"
        });
        if (!(target instanceof Entity)) {
            target = readFirstNonNullField(unit, "hywTarget", "attackTarget", "combatTarget", "targetEntity", "trackedTarget");
        }
        return target instanceof Entity entity && entity.isAlive() && entity != unit ? entity : null;
    }

    /** 记录单位最近一次真实攻击目标，供移动指令抑制目标时沿用。 */
    private static void rememberEngagedTarget(LivingEntity unit, Entity target) {
        LAST_ENGAGED_TARGET.put(unit, target);
    }

    /** 取出仍存活且仍在交战范围内的最近目标；否则丢弃返回 null。 */
    private static Entity lastEngagedTarget(LivingEntity unit, Entity origin) {
        Entity latched = LAST_ENGAGED_TARGET.get(unit);
        if (latched == null) {
            return null;
        }
        if (!latched.isAlive() || latched.isRemoved()) {
            forgetEngagedTarget(unit);
            return null;
        }
        Entity reference = origin != null ? origin : unit;
        if (reference.position().distanceToSqr(latched.position()) > TARGET_ENGAGE_RANGE_SQR) {
            forgetEngagedTarget(unit);
            return null;
        }
        return latched;
    }

    private static void forgetEngagedTarget(LivingEntity unit) {
        LAST_ENGAGED_TARGET.remove(unit);
    }

    /** “停火”时即使仍有目标在范围内也要清掉缓存，避免误开火。 */
    private static boolean isExplicitCeaseFire(LivingEntity unit) {
        Object strategy = invokeFirstNonNull(unit, new String[]{"getAttackStrategy", "getActionStrategy", "getCombatStrategy"});
        if (strategy == null) {
            strategy = readFirstNonNullField(unit, "attackStrategy", "actionStrategy", "combatStrategy");
        }
        if (strategy == null) {
            return false;
        }
        Object id = invokeFirstNonNull(strategy, new String[]{"getId", "getName"});
        String value = String.valueOf(id != null ? id : strategy).toLowerCase(Locale.ROOT);
        return value.contains("cease") || value.contains("停火");
    }

    /**
     * 解析单位的移动目的地。
     *
     * @param includeNavigator 是否允许回退到单位原生寻路目标。“仅指令”锁定敌人时应传 false，
     *                         避免士兵自身的追击寻路把车体带过去，冲掉玩家的移动指令。
     */
    private static Object resolveMovementTarget(LivingEntity unit, boolean includeNavigator) {
        // 跟随目标是动态实体，必须优先于旧的导航坐标，否则车辆会追到跟随者的旧位置。
        Object followTarget = invokeFirstNonNull(unit, new String[]{
                "getActiveFollowTargetEntity", "getFollowVehicleTarget", "getFollowTarget"
        });
        followTarget = normalizeMovementTarget(followTarget);
        if (followTarget != null && !(followTarget instanceof Entity entity && entity == unit)) {
            return followTarget;
        }

        // RTS 指挥点可能保存在士兵模组的移动 Goal 中，不能只查原生导航器。
        Object target = resolveCommandIntent(unit).movementTarget();
        if (target != null) {
            return target;
        }

        // 巡逻目标点直接保存在 BaseCombatEntity 的 patrolPoints 中；当前 PatrolGoal
        // 还没来得及暴露 targetPos 时，至少沿第一条有效路点继续巡逻。
        Object patrolPoints = invokeFirstNonNull(unit, new String[]{"getPatrolPoints"});
        if (patrolPoints == null) {
            patrolPoints = readField(unit, "patrolPoints");
        }
        Object patrolTarget = selectRoutePoint(patrolPoints, unit);
        patrolTarget = normalizeMovementTarget(patrolTarget);
        if (patrolTarget != null) {
            return patrolTarget;
        }

        // RTS 视角下发的是士兵模组自己的位置目标，不一定会进入 Minecraft 导航器。
        target = invokeFirstNonNull(unit, new String[]{ 
                "getPathingPositionTarget", "getMovementTarget", "getMoveTarget", "getRtsTarget",
                "getCommandTarget", "getPositionTarget", "getPathingTarget", "getDestination"
        });
        target = normalizeMovementTarget(target);
        if (target instanceof Entity entity && entity == unit) {
            target = null;
        }
        if (target != null) {
            return target;
        }
        target = normalizeMovementTarget(readFirstNonNullField(unit,
                "pathingPositionTarget", "movementTarget", "moveTarget", "rtsTarget",
                "commandTarget", "positionTarget", "pathingTarget", "destination"));
        if (target instanceof Entity entity && entity == unit) {
            target = null;
        }
        if (target != null) {
            return target;
        }
        if (includeNavigator && unit instanceof Mob mob) {
            Object navigation = mob.getNavigation();
            Object position = unwrapOptional(invokeFirst(navigation, new String[]{"getTargetPos", "getTargetPosition", "getPathTarget"}));
            if (position instanceof BlockPos || position instanceof Vec3) {
                return position;
            }
        }
        return null;
    }

    /** 自由探索不依赖固定路点，而是在当前位置附近持续抽取可到达的随机路点。 */
    private static Object resolveFreeRoamTarget(LivingEntity unit, Entity vehicle, Object ignoredCommandTarget) {
        Vec3 current = FREE_ROAM_TARGETS.get(vehicle);
        boolean reached = current == null || horizontalDistance(vehicle.position(), current) <= FREE_ROAM_REACHED_DISTANCE;
        int stuckTicks = NAVIGATION_STUCK_TICKS.getOrDefault(vehicle, 0);
        if (!reached && stuckTicks < STUCK_TICKS_BEFORE_REVERSE) {
            return current;
        }

        if (!reached && isTargetBehind(vehicle, current)) {
            Integer escapeUntil = REVERSE_ESCAPE_UNTIL.get(vehicle);
            if (escapeUntil != null && escapeUntil > vehicle.tickCount) {
                // 倒车周期尚未结束，保留原目标让载具继续脱困。
                return current;
            }
            if (escapeUntil == null && stuckTicks == STUCK_TICKS_BEFORE_REVERSE) {
                // 第一次触发时保留正后方目标，由 driveControlPort 执行 30 tick 倒车。
                return current;
            }
            REVERSE_ESCAPE_UNTIL.remove(vehicle);
        }

        Vec3 next = randomFreeRoamTarget(vehicle);
        if (next != null) {
            FREE_ROAM_TARGETS.put(vehicle, next);
            LAST_NAVIGATION_POSITION.put(vehicle, vehicle.position());
            NAVIGATION_STUCK_TICKS.remove(vehicle);
            return next;
        }
        return reached ? null : current;
    }

    private static Vec3 randomFreeRoamTarget(Entity vehicle) {
        if (vehicle.level().isClientSide) {
            return null;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int originX = vehicle.blockPosition().getX();
        int originZ = vehicle.blockPosition().getZ();
        for (int attempt = 0; attempt < FREE_ROAM_MAX_ATTEMPTS; attempt++) {
            double angle = random.nextDouble(0.0D, Math.PI * 2.0D);
            double radius = random.nextDouble(FREE_ROAM_MIN_RADIUS, FREE_ROAM_MAX_RADIUS);
            int x = (int) Math.floor(originX + Math.cos(angle) * radius);
            int z = (int) Math.floor(originZ + Math.sin(angle) * radius);
            int y = vehicle.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            Vec3 candidate = new Vec3(x + 0.5D, y, z + 0.5D);
            Vec3 offset = candidate.subtract(vehicle.position());
            if (vehicle.level().noCollision(vehicle.getBoundingBox().move(offset))) {
                return candidate;
            }
        }
        return null;
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        double x = first.x - second.x;
        double z = first.z - second.z;
        return Math.sqrt(x * x + z * z);
    }

    private static boolean isTargetBehind(Entity vehicle, Vec3 target) {
        Vec3 horizontalTarget = new Vec3(target.x - vehicle.getX(), 0.0D, target.z - vehicle.getZ());
        if (horizontalTarget.lengthSqr() < 1.0E-6D) {
            return false;
        }
        return directionFromYaw(vehicle.getYRot()).dot(horizontalTarget.normalize()) <= REAR_TARGET_DOT;
    }

    private static CommandIntent resolveCommandIntent(LivingEntity unit) {
        Object prioritizedGoal = unwrapOptional(invokeFirstNonNull(unit, new String[]{
                "getCurrentCommandedGoal", "getCurrentCommandGoal", "getActiveCommandedGoal",
                "getCurrentOrder", "getActiveOrder", "getCommand"
        }));
        if (prioritizedGoal == null) {
            prioritizedGoal = readFirstNonNullField(unit,
                    "currentCommandedGoal", "currentCommandGoal", "activeCommandedGoal",
                    "currentOrder", "activeOrder", "command");
        }
        Object goal = unwrapOptional(readFirstNonNullField(prioritizedGoal, "goal", "commandGoal", "orderGoal"));
        if (goal == null) {
            goal = prioritizedGoal;
        }

        Object movementTarget = findPosition(goal, new HashSet<>(), 0,
                "movementTargetPos", "targetPos", "currentPatrolPos", "currentPatrolPoint",
                "patrolTarget", "patrolPos", "waypoint", "currentWaypoint", "destination", "positionTarget");
        if (movementTarget == null) {
            movementTarget = findPosition(prioritizedGoal, new HashSet<>(), 0,
                    "movementTargetPos", "targetPos", "currentPatrolPos", "currentPatrolPoint",
                    "patrolTarget", "patrolPos", "waypoint", "currentWaypoint", "destination", "positionTarget");
        }

        Vec3 facingTarget = findFacingTarget(goal, unit);
        if (facingTarget == null) {
            facingTarget = findFacingTarget(prioritizedGoal, unit);
        }
        if (facingTarget == null) {
            facingTarget = resolveUnitFacingTarget(unit);
        }
        StrategyMode strategyMode = resolveStrategyMode(unit);
        return new CommandIntent(normalizeMovementTarget(movementTarget), facingTarget,
                strategyMode != StrategyMode.ORDERS_ONLY, strategyMode == StrategyMode.FREE_ROAM);
    }

    /** 读取百年战争的行动策略；自由探索必须单独处理，不能当成带固定路点的战斗模式。 */
    private static StrategyMode resolveStrategyMode(LivingEntity unit) {
        Object autoCombat = invokeFirstNonNull(unit, new String[]{"isAutoCombatStrategy"});
        if (autoCombat instanceof Boolean value && !value) {
            return StrategyMode.ORDERS_ONLY;
        }

        Object strategy = invokeFirstNonNull(unit, new String[]{"getAttackStrategy", "getActionStrategy", "getCombatStrategy"});
        if (strategy == null) {
            strategy = readFirstNonNullField(unit, "attackStrategy", "actionStrategy", "combatStrategy");
        }
        if (strategy == null) {
            return autoCombat instanceof Boolean && (Boolean) autoCombat
                    ? StrategyMode.AUTONOMOUS_COMBAT : StrategyMode.AUTONOMOUS_COMBAT;
        }
        Object id = invokeFirstNonNull(strategy, new String[]{"getId", "getName"});
        String value = String.valueOf(id != null ? id : strategy).toLowerCase(Locale.ROOT);
        if (value.contains("free_roam") || value.contains("free roam")
                || value.contains("roam") || value.contains("explor")
                || value.contains("自由探索") || value.contains("探索")) {
            return StrategyMode.FREE_ROAM;
        }
        if (value.contains("default") || value.contains("command") || value.contains("cease")
                || value.contains("仅指令") || value.contains("停火")) {
            return StrategyMode.ORDERS_ONLY;
        }
        return StrategyMode.AUTONOMOUS_COMBAT;
    }

    /** 读取百年战争的行动策略；其他单位没有该接口时保持原有的自动战斗兼容。 */
    private static boolean isAutonomousCombatStrategy(LivingEntity unit) {
        return resolveStrategyMode(unit) != StrategyMode.ORDERS_ONLY;
    }

    /** 百年战争“设置朝向”直接保存在士兵本体，不一定挂在当前指令对象上。 */
    private static Vec3 resolveUnitFacingTarget(LivingEntity unit) {
        Object hasFacing = invokeFirstNonNull(unit, new String[]{"hasIdleFacingDirection", "hasFacingDirection"});
        if (Boolean.TRUE.equals(hasFacing)) {
            Object yaw = invokeFirstNonNull(unit, new String[]{"getIdleFacingYaw", "getFacingYaw"});
            if (yaw instanceof Number number) {
                return unit.position().add(directionFromYaw(number.floatValue()).scale(64.0D));
            }
        }
        return null;
    }

    private static Object findPosition(Object source, Set<Object> visited, int depth, String... names) {
        if (source == null || depth > 2 || visited.contains(source)) {
            return null;
        }
        visited.add(source);
        Object direct = normalizeMovementTarget(invokeFirstNonNull(source, getterNames(names)));
        if (direct == null) {
            direct = normalizeMovementTarget(readFirstNonNullField(source, names));
        }
        if (direct != null) {
            return direct;
        }

        Object route = invokeFirstNonNull(source, new String[]{
                "getCurrentPatrolPoint", "getCurrentWaypoint", "getNextWaypoint", "getPatrolTarget",
                "getPatrolPoints", "getWaypoints", "getRoute", "getPathPoints"
        });
        if (route == null) {
            route = readFirstNonNullField(source,
                    "currentPatrolPoint", "currentWaypoint", "nextWaypoint", "patrolTarget",
                    "patrolPoints", "waypoints", "route", "pathPoints");
        }
        Object routePoint = selectRoutePoint(route, source);
        direct = normalizeMovementTarget(routePoint);
        if (direct != null) {
            return direct;
        }
        return findPosition(routePoint, visited, depth + 1, names);
    }

    private static Object selectRoutePoint(Object route, Object owner) {
        route = unwrapOptional(route);
        if (route == null) {
            return null;
        }
        if (normalizeMovementTarget(route) != null) {
            return route;
        }
        List<Object> points = new ArrayList<>();
        for (Object value : valuesOf(route)) {
            points.add(value);
        }
        if (points.isEmpty()) {
            return route;
        }
        Object indexValue = readFirstNonNullField(owner,
                "patrolIndex", "currentPatrolIndex", "waypointIndex", "currentWaypointIndex", "routeIndex");
        if (!(indexValue instanceof Number)) {
            indexValue = invokeFirstNonNull(owner, new String[]{
                    "getPatrolIndex", "getCurrentPatrolIndex", "getWaypointIndex", "getCurrentWaypointIndex", "getRouteIndex"
            });
        }
        int index = indexValue instanceof Number number ? number.intValue() : 0;
        return points.get(Math.floorMod(index, points.size()));
    }

    private static Vec3 findFacingTarget(Object source, LivingEntity unit) {
        if (source == null) {
            return null;
        }
        String[] targetNames = new String[]{
                "facingTarget", "facingTargetPos", "targetPosition", "lookTarget", "lookTargetPos",
                "rotationTarget", "directionTarget", "headingTarget", "lookAtPos", "lookPos"
        };
        Object target = normalizeMovementTarget(invokeFirstNonNull(source, getterNames(targetNames)));
        if (target == null) {
            target = normalizeMovementTarget(readFirstNonNullField(source, targetNames));
        }
        Vec3 position = targetPosition(target);
        if (position != null) {
            return position;
        }

        Object direction = invokeFirstNonNull(source, new String[]{
                "getFacingDirection", "getLookDirection", "getCommandDirection", "getSetDirection", "getHeading"
        });
        if (direction == null) {
            direction = readFirstNonNullField(source,
                    "facingDirection", "lookDirection", "commandDirection", "setDirection", "heading");
        }
        Vec3 directionVec = asDirection(direction);
        if (directionVec != null) {
            return unit.position().add(directionVec.scale(64.0D));
        }

        Object yaw = invokeFirstNonNull(source, new String[]{
                "getFacingYaw", "getTargetYaw", "getCommandYaw", "getSetYaw", "getYaw", "getDirectionYaw"
        });
        if (yaw == null) {
            yaw = readFirstNonNullField(source,
                    "facingYaw", "targetYaw", "commandYaw", "setYaw", "yaw", "directionYaw");
        }
        if (yaw instanceof Number number) {
            return unit.position().add(directionFromYaw(number.floatValue()).scale(64.0D));
        }
        return null;
    }

    private static String[] getterNames(String[] names) {
        String[] getters = new String[names.length];
        for (int index = 0; index < names.length; index++) {
            getters[index] = "get" + Character.toUpperCase(names[index].charAt(0)) + names[index].substring(1);
        }
        return getters;
    }

    private static void sendMovementOrder(Entity vehicle, Object target) {
        Vec3 targetPos = targetPosition(target);
        if (targetPos == null) {
            return;
        }
        // RVP 载具使用自己的控制端口，其他模组的原生载具不会进入 isVehicle()。
        invokeFirst(vehicle, new String[]{
                "setPathingPositionTarget", "setMovementTarget", "setMoveTarget", "setCommandTarget"
        }, target);
        invokeFirst(vehicle, new String[]{"setPositionTarget", "setPathingTarget"}, targetPos);
        driveControlPort(vehicle, targetPos, true);
    }

    private static Object invokeFirstNonNull(Object object, String[] names) {
        for (String name : names) {
            Object result = unwrapOptional(invoke(object, name));
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static Object readFirstNonNullField(Object object, String... names) {
        for (String name : names) {
            Object value = unwrapOptional(readField(object, name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object normalizeMovementTarget(Object target) {
        if (target instanceof Entity entity) {
            return entity.isAlive() ? entity : null;
        }
        return target instanceof Vec3 || target instanceof BlockPos ? target : null;
    }

    /** 只清理本桥接负责的瞬时移动输入，保留 RVP 的目标、驾驶员和其他控制状态。 */
    private static void clearMovementInput(Entity vehicle) {
        Object control = getControlPort(vehicle);
        if (control == null) {
            return;
        }
        for (String field : new String[]{
                "forward", "backward", "up", "down", "functionalUp", "functionalDown",
                "left", "right", "functionalLeft", "functionalRight", "leftYaw", "rightYaw"
        }) {
            setBoolean(control, field, false);
        }
    }

    private static Object getControlPort(Entity vehicle) {
        Object control = invokeFirst(vehicle, new String[]{"getControlUnit", "getControlPort", "getVehicleControl"});
        return control != null ? control : readField(vehicle, "controlUnit");
    }

    private static void aimIdleWeaponPorts(Entity vehicle, Object operatorPort, Vec3 aimPoint) {
        if (aimPoint == null) {
            return;
        }
        Set<Object> ports = new HashSet<>();
        if (operatorPort != null) {
            ports.add(operatorPort);
        }
        Object allParts = unwrapOptional(invokeFirst(vehicle,
                new String[]{"getPartUnits", "getOperatorUnits", "getPartPorts", "getWeaponUnits"}));
        for (Object part : valuesOf(allParts)) {
            if (part != null && (hasMethod(part, "aim") || hasMethod(part, "setAimTarget"))) {
                ports.add(part);
            }
        }
        Object fallback = findFirstWeaponPort(vehicle);
        if (fallback != null) {
            ports.add(fallback);
        }
        for (Object port : ports) {
            invoke(port, "aim", aimPoint);
            invokeFirst(port, new String[]{"setAimTarget", "setTarget", "setTrackedTarget"}, aimPoint);
            invokeFirst(port, new String[]{"setFiring", "setFire", "setAttacking", "setShooting"}, false);
        }
    }

    private static Vec3 forwardAimPoint(Entity vehicle) {
        return forwardAimPoint(vehicle, vehicle.getYRot());
    }

    /** 以指定航向给出车体正前方的瞄准点；用于让炮塔超前于本 tick 的转向指令，而不是跟在过期朝向后追。 */
    private static Vec3 forwardAimPoint(Entity vehicle, float yaw) {
        return vehicle.position().add(directionFromYaw(yaw).scale(64.0D));
    }

    private static void turnControlPortToYaw(Entity vehicle, float desiredYaw) {
        Object control = getControlPort(vehicle);
        if (control == null) {
            return;
        }
        float error = wrapDegrees(desiredYaw - vehicle.getYRot());
        writeNumberField(control, "yRot", desiredYaw);
        setBoolean(control, "yRotKeep", false);
        if (Math.abs(error) < 3.0F) {
            return;
        }
        boolean left = error < 0.0F;
        setBoolean(control, "left", left);
        setBoolean(control, "right", !left);
        setBoolean(control, "functionalLeft", left);
        setBoolean(control, "functionalRight", !left);
        setBoolean(control, "leftYaw", left);
        setBoolean(control, "rightYaw", !left);
    }

    private static float yawTowards(Vec3 origin, Vec3 target) {
        Vec3 delta = target.subtract(origin);
        return (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
    }

    private static Vec3 directionFromYaw(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0.0D, Math.cos(radians));
    }

    private static Vec3 asDirection(Object value) {
        value = unwrapOptional(value);
        if (value instanceof Vec3 vec && vec.lengthSqr() > 1.0E-6D) {
            return vec.normalize();
        }
        if (value instanceof net.minecraft.core.Direction direction) {
            return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ()).normalize();
        }
        if (value instanceof Number number) {
            return directionFromYaw(number.floatValue());
        }
        return null;
    }

    private static float wrapDegrees(float value) {
        float result = value % 360.0F;
        if (result >= 180.0F) result -= 360.0F;
        if (result < -180.0F) result += 360.0F;
        return result;
    }

    /** 探测车头前方空间；一格高且上方有空间的障碍交给载具的正常越障能力处理。 */
    private static boolean hasObstacleAhead(Entity vehicle, float currentYaw) {
        if (vehicle.level().isClientSide) {
            return false;
        }
        Vec3 forward = directionFromYaw(currentYaw);
        AABB probe = vehicle.getBoundingBox()
                .expandTowards(forward.scale(FORWARD_OBSTACLE_LOOKAHEAD))
                .inflate(REVERSE_ESCAPE_CLEARANCE, 0.0D, REVERSE_ESCAPE_CLEARANCE);
        return vehicle.horizontalCollision || !vehicle.level().noCollision(probe);
    }

    /** 判断前方碰撞是否只是可以直接跨过的一格高障碍。 */
    private static boolean canClearOneBlockObstacle(Entity vehicle, float currentYaw) {
        if (vehicle.level().isClientSide) {
            return false;
        }
        Vec3 forward = directionFromYaw(currentYaw);
        AABB raisedProbe = vehicle.getBoundingBox()
                .expandTowards(forward.scale(FORWARD_OBSTACLE_LOOKAHEAD))
                .move(0.0D, STEP_CLEARANCE_HEIGHT, 0.0D)
                .inflate(REVERSE_ESCAPE_CLEARANCE, 0.0D, REVERSE_ESCAPE_CLEARANCE);
        return vehicle.level().noCollision(raisedProbe);
    }

    /** 记录导航目标下的实际位移；仅连续卡住时才允许进入倒车脱困。 */
    private static boolean updateNavigationStuck(Entity vehicle, boolean moving) {
        Vec3 current = vehicle.position();
        Vec3 previous = LAST_NAVIGATION_POSITION.put(vehicle, current);
        if (!moving) {
            NAVIGATION_STUCK_TICKS.remove(vehicle);
            return false;
        }
        int stuckTicks = NAVIGATION_STUCK_TICKS.getOrDefault(vehicle, 0);
        if (previous != null && current.distanceToSqr(previous) < MIN_PROGRESS_SQR) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        NAVIGATION_STUCK_TICKS.put(vehicle, stuckTicks);
        return stuckTicks >= STUCK_TICKS_BEFORE_REVERSE;
    }

    /** 倒车只在导航目标位于车体正后方且载具确实被不可跨越障碍卡住时启动。 */
    private static boolean updateReverseEscape(Entity vehicle, boolean reverseRequested) {
        int now = vehicle.tickCount;
        Integer escapeUntil = REVERSE_ESCAPE_UNTIL.get(vehicle);
        if (escapeUntil != null && escapeUntil > now) {
            return true;
        }
        if (escapeUntil != null) {
            REVERSE_ESCAPE_UNTIL.remove(vehicle);
        }
        if (reverseRequested) {
            REVERSE_ESCAPE_UNTIL.put(vehicle, now + REVERSE_ESCAPE_TICKS);
            return true;
        }
        return false;
    }

    private static void driveControlPort(Entity vehicle, Object target, boolean navigationTarget) {
        Vec3 targetPos = targetPosition(target);
        if (targetPos == null) {
            return;
        }
        if (!navigationTarget) {
            REVERSE_ESCAPE_UNTIL.remove(vehicle);
            LAST_NAVIGATION_POSITION.remove(vehicle);
            NAVIGATION_STUCK_TICKS.remove(vehicle);
        }
        Vec3 delta = targetPos.subtract(vehicle.position());
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        float yawError = wrapDegrees(desiredYaw - vehicle.getYRot());
        Object control = getControlPort(vehicle);
        if (control == null) {
            return;
        }

        boolean moving = horizontalDistance > STOP_DISTANCE;
        boolean obstacleAhead = navigationTarget && moving && hasObstacleAhead(vehicle, vehicle.getYRot());
        boolean stepableObstacle = obstacleAhead && canClearOneBlockObstacle(vehicle, vehicle.getYRot());
        boolean stuck = navigationTarget && updateNavigationStuck(vehicle, moving);
        Vec3 forward = directionFromYaw(vehicle.getYRot());
        Vec3 horizontalTarget = new Vec3(delta.x, 0.0D, delta.z);
        boolean targetBehind = horizontalTarget.lengthSqr() > 1.0E-6D
                && forward.dot(horizontalTarget.normalize()) <= REAR_TARGET_DOT;
        boolean reversing = navigationTarget
                && !stepableObstacle
                && updateReverseEscape(vehicle, stuck && obstacleAhead && targetBehind);
        // 攻击目标不参与倒车；无敌人时默认前进，一格高障碍不触发倒车。
        setBoolean(control, "forward", moving && !reversing);
        setBoolean(control, "backward", reversing);
        boolean turnLeft = yawError < -10.0F;
        boolean turnRight = yawError > 10.0F;
        writeNumberField(control, "yRot", desiredYaw);
        setBoolean(control, "yRotKeep", false);
        setBoolean(control, "left", turnLeft);
        setBoolean(control, "right", turnRight);
        setBoolean(control, "functionalLeft", turnLeft);
        setBoolean(control, "functionalRight", turnRight);
        setBoolean(control, "leftYaw", turnLeft);
        setBoolean(control, "rightYaw", turnRight);
        setBoolean(control, "functionalUp", delta.y > 4.0D);
        setBoolean(control, "functionalDown", delta.y < -4.0D);
        setBoolean(control, "up", delta.y > 4.0D);
        setBoolean(control, "down", delta.y < -4.0D);
    }

    private static void applyTargetToVehicle(Entity vehicle, Object operatorPort, Entity target) {
        invokeFirst(vehicle, new String[]{
                "setTarget", "setAttackTarget", "setTrackedTarget", "setAimTarget", "setFireTarget"
        }, target);
        setEntityFields(vehicle, target, "target", "attackTarget", "trackedTarget", "aimTarget", "fireTarget");
        applyAttackPort(operatorPort, target);
    }

    private static void applyAttackPort(Object operatorPort, Entity target) {
        if (operatorPort == null) {
            return;
        }
        invokeFirst(operatorPort, new String[]{
                "setTarget", "setAttackTarget", "setTrackedTarget", "setAimTarget", "setFireTarget", "setTargetEntity"
        }, target);
        setEntityFields(operatorPort, target, "target", "attackTarget", "trackedTarget", "aimTarget", "fireTarget", "targetEntity");
        // 这里只登记目标并发起瞄准，不能在炮口转动期间打开持续开火开关。
        invokeFirst(operatorPort, new String[]{"aimAt", "attackTarget", "fireAt", "shootAt", "engage"}, target);
    }

    /** 只有当前炮口已经指向目标时，才打开开火状态并调用真实 shoot 端口。 */
    private static void fireWeaponPort(Object operatorPort, LivingEntity unit, Entity target) {
        fireWeaponPort(operatorPort, unit, target, unit.getVehicle(), false);
    }

    private static void fireWeaponPort(Object operatorPort, LivingEntity unit, Entity target,
                                       Entity vehicle, boolean updateVehicleAttack) {
        Object weaponPort = operatorPort;
        if (weaponPort == null) {
            return;
        }
        Vec3 aimPoint = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        invoke(weaponPort, "aim", aimPoint);
        Object weapons = unwrapOptional(invokeFirst(weaponPort, new String[]{"getIndexedWeapons", "getWeapons"}));
        int weaponIndex = firstUsableWeaponIndex(weapons);
        boolean aligned = weaponIndex >= 0 && isWeaponAligned(weaponPort, aimPoint, vehicle);
        if (!aligned) {
            // 清掉上一 tick 遗留的开火输入，等炮口真正转到位后再开炮。
            invokeFirst(weaponPort, new String[]{"setFiring", "setFire", "setAttacking", "setShooting"}, false);
            if (updateVehicleAttack) {
                invokeFirst(vehicle, new String[]{"setStartAttacking", "setAttacking", "setForceAttackTarget", "setForceAttack"}, false);
            }
            return;
        }
        invokeFirst(weaponPort, new String[]{"setFiring", "setFire", "setAttacking", "setShooting"}, true);
        if (updateVehicleAttack) {
            invokeFirst(vehicle, new String[]{"setStartAttacking", "setAttacking", "setForceAttackTarget", "setForceAttack"}, true);
        }
        if (!hasMethod(weaponPort, "shoot")) {
            return;
        }
        Object contexts = unwrapOptional(invokeFirst(weaponPort, new String[]{"aimContexts", "getAimContexts"}));
        if (contexts == null) {
            contexts = List.of();
        }
        // RVP 的真实签名为 shoot(int, List, LivingEntity)。只在该签名存在时调用。
        invoke(weaponPort, "shoot", weaponIndex, contexts, unit);
    }

    /** 用端口的当前世界方向与目标方向比较，避免“刚锁定就开炮”。 */
    private static boolean isWeaponAligned(Object weaponPort, Vec3 aimPoint, Entity vehicle) {
        Vec3 origin = firstVec(weaponPort, new String[]{
                "worldPivotPosition", "worldCurrentBoltPosition", "worldPosition", "getPosition"
        });
        if (origin == null && vehicle != null) {
            origin = vehicle.position();
        }
        Vec3 currentDirection = firstVec(weaponPort, new String[]{
                "worldVec", "getWorldVec", "getAimDirection", "getLaunchDirection", "getBarrelDirection", "getMuzzleDirection"
        });
        if (currentDirection == null) {
            Object worldRot = invokeFirst(weaponPort, new String[]{"worldRot", "getWorldRot"});
            currentDirection = directionFromRotation(worldRot);
        }
        if (currentDirection == null) {
            Object xRot = invokeFirst(weaponPort, new String[]{"getXAimRot", "getXRot"});
            Object yRot = invokeFirst(weaponPort, new String[]{"getYAimRot", "getYRot"});
            if (xRot instanceof Number x && yRot instanceof Number y) {
                currentDirection = asVec3(invoke(weaponPort, "worldVec", x.floatValue(), y.floatValue()));
            }
        }
        if (origin == null || currentDirection == null || currentDirection.lengthSqr() < 1.0E-6D) {
            // 无法读取真实炮口方向时宁可不发射，也不退回到无条件开火。
            return false;
        }
        Vec3 desiredDirection = aimPoint.subtract(origin);
        if (desiredDirection.lengthSqr() < 1.0E-6D) {
            return true;
        }
        double dot = currentDirection.normalize().dot(desiredDirection.normalize());
        double clampedDot = Math.max(-1.0D, Math.min(1.0D, dot));
        double angle = Math.toDegrees(Math.acos(clampedDot));
        return angle <= WEAPON_ALIGNMENT_TOLERANCE_DEGREES;
    }

    private static Vec3 firstVec(Object object, String[] names) {
        for (String name : names) {
            Vec3 value = asVec3(invoke(object, name));
            if (value != null && value.lengthSqr() >= 1.0E-6D) {
                return value;
            }
        }
        return null;
    }

    private static Vec3 asVec3(Object value) {
        value = unwrapOptional(value);
        return value instanceof Vec3 vec ? vec : null;
    }

    private static Vec3 directionFromRotation(Object value) {
        if (!(value instanceof net.minecraft.world.phys.Vec2 rotation)) {
            return null;
        }
        double pitch = Math.toRadians(rotation.x);
        double yaw = Math.toRadians(rotation.y);
        double horizontal = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * horizontal, -Math.sin(pitch), Math.cos(yaw) * horizontal);
    }

    private static Object findFirstWeaponPort(Entity vehicle) {
        Object parts = unwrapOptional(invokeFirst(vehicle, new String[]{"getPartUnits", "getOperatorUnits", "getPartPorts"}));
        for (Object part : valuesOf(parts)) {
            if (part != null && hasMethod(part, "shoot") && !valuesOf(invokeFirst(part, new String[]{"getIndexedWeapons", "getWeapons"})).iterator().hasNext()) {
                continue;
            }
            if (part != null && hasMethod(part, "shoot")) {
                return part;
            }
        }
        Object fieldParts = readField(vehicle, "partUnits");
        for (Object part : valuesOf(fieldParts)) {
            if (part != null && hasMethod(part, "shoot")) {
                return part;
            }
        }
        return null;
    }

    private static int firstUsableWeaponIndex(Object weapons) {
        int index = 0;
        for (Object weapon : valuesOf(weapons)) {
            if (weapon != null) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static void clearOperatorPort(Object operatorPort) {
        if (operatorPort == null) {
            return;
        }
        invokeFirst(operatorPort, new String[]{
                "setTarget", "setAttackTarget", "setTrackedTarget", "setAimTarget", "setFireTarget", "setTargetEntity"
        }, (Object) null);
        clearEntityFields(operatorPort, "target", "attackTarget", "trackedTarget", "aimTarget", "fireTarget", "targetEntity");
        invokeFirst(operatorPort, new String[]{"setFiring", "setFire", "setAttacking", "setShooting"}, false);
    }

    private static void clearTargetFromVehicle(Entity vehicle, Object operatorPort) {
        invokeFirst(vehicle, new String[]{
                "setTarget", "setAttackTarget", "setTrackedTarget", "setAimTarget", "setFireTarget"
        }, (Object) null);
        invokeFirst(vehicle, new String[]{"setStartAttacking", "setAttacking", "setForceAttackTarget", "setForceAttack"}, false);
        clearEntityFields(vehicle, "target", "attackTarget", "trackedTarget", "aimTarget", "fireTarget");
        if (operatorPort != null) {
            invokeFirst(operatorPort, new String[]{
                    "setTarget", "setAttackTarget", "setTrackedTarget", "setAimTarget", "setFireTarget", "setTargetEntity"
            }, (Object) null);
            clearEntityFields(operatorPort, "target", "attackTarget", "trackedTarget", "aimTarget", "fireTarget", "targetEntity");
            invokeFirst(operatorPort, new String[]{"setFiring", "setFire", "setAttacking", "setShooting"}, false);
        }
    }

    private static void stopVehicle(Entity vehicle) {
        REVERSE_ESCAPE_UNTIL.remove(vehicle);
        LAST_NAVIGATION_POSITION.remove(vehicle);
        NAVIGATION_STUCK_TICKS.remove(vehicle);
        Object control = getControlPort(vehicle);
        invoke(control, "reset");
        clearBooleanFields(control);
        invokeFirst(vehicle, new String[]{"stopActiveMovementIntent", "stopCrossDomainMovement"});
        if (vehicle instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        invokeFirst(vehicle, new String[]{"clearPositionTarget", "clearMovementTarget", "clearPathingTarget"});
    }

    private static Vec3 targetPosition(Object target) {
        if (target instanceof Entity entity) {
            return entity.position();
        }
        if (target instanceof Vec3 vec) {
            return vec;
        }
        if (target instanceof BlockPos pos) {
            return Vec3.atCenterOf(pos);
        }
        return null;
    }

    private static Object findSeatForEntity(Entity vehicle, Entity unit) {
        Object mappedSeat = unwrapOptional(invoke(vehicle, "getSeatForEntity", unit));
        if (mappedSeat != null) {
            return mappedSeat;
        }
        Object seatMap = readField(vehicle, "entitySeatMap");
        if (seatMap instanceof Map<?, ?> map) {
            Object seat = map.get(unit);
            if (seat == null) {
                seat = map.get(unit.getId());
            }
            if (seat != null) {
                return unwrapOptional(seat);
            }
        }
        Object seats = readField(vehicle, "seats");
        for (Object seat : valuesOf(seats)) {
            Object passengerId = readField(seat, "passengerId");
            if (passengerId instanceof Number number && number.intValue() == unit.getId()) {
                return seat;
            }
            Object passenger = unwrapOptional(invokeFirst(seat, new String[]{"getPassenger", "getEntity", "getOccupant"}));
            if (passenger == unit) {
                return seat;
            }
        }
        return null;
    }

    private static Object getCrewPort(Entity vehicle, Entity unit) {
        return unwrapOptional(invoke(vehicle, "getOwnOperatorUnit", unit));
    }

    private static boolean isDriverRole(Object seatOrPort) {
        if (seatOrPort == null) {
            return false;
        }
        Object role = invokeFirst(seatOrPort, new String[]{"isDriver", "isPilot"});
        if (Boolean.TRUE.equals(role)) {
            return true;
        }
        for (String name : new String[]{"role", "type", "seatType", "partType", "operatorType", "name", "id"}) {
            Object value = readField(seatOrPort, name);
            if (value != null && isDriverName(String.valueOf(value))) {
                return true;
            }
        }
        Object value = invokeFirst(seatOrPort, new String[]{"getRole", "getType", "getSeatType", "getPartType", "getName", "getId"});
        return value != null && isDriverName(String.valueOf(value));
    }

    private static boolean isDriverName(String value) {
        String role = value.toLowerCase(Locale.ROOT);
        return role.contains("driver") || role.contains("pilot") || role.contains("engineer")
                || role.contains("驾驶") || role.contains("飞行") || role.contains("工程师");
    }

    private static boolean hasExplicitRvpPort(Entity vehicle) {
        return hasField(vehicle, "controlUnit") || hasMethod(vehicle, "getOwnOperatorUnit")
                || hasMethod(vehicle, "changeSeat");
    }

    private static void clearRegisteredSeat(Entity vehicle, Entity unit) {
        Object seatMap = readField(vehicle, "entitySeatMap");
        if (seatMap instanceof Map<?, ?> map) {
            ((Map<?, ?>) map).remove(unit);
        }
        Object seats = readField(vehicle, "seats");
        for (Object seat : valuesOf(seats)) {
            Object passengerId = readField(seat, "passengerId");
            if (passengerId instanceof Number number && number.intValue() == unit.getId()) {
                writeField(seat, "passengerId", -1);
            }
        }
    }

    private static void setEntityFields(Object object, Entity value, String... names) {
        for (String name : names) {
            Field field = findField(object, name);
            if (field != null && field.getType().isAssignableFrom(value.getClass())) {
                writeField(object, name, value);
            }
        }
    }

    private static void clearEntityFields(Object object, String... names) {
        for (String name : names) {
            Field field = findField(object, name);
            if (field != null && !field.getType().isPrimitive() && Entity.class.isAssignableFrom(field.getType())) {
                writeField(object, name, null);
            }
        }
    }

    private static void clearBooleanFields(Object object) {
        if (object == null) {
            return;
        }
        Class<?> current = object.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() == boolean.class) {
                    try {
                        field.setAccessible(true);
                        field.setBoolean(object, false);
                    } catch (Throwable ignored) {
                    }
                }
            }
            current = current.getSuperclass();
        }
    }

    private static void setBoolean(Object object, String fieldName, boolean value) {
        if (object == null) {
            return;
        }
        Field field = findField(object, fieldName);
        if (field != null && field.getType() == boolean.class) {
            writeField(object, fieldName, value);
        } else {
            invokeFirst(object, new String[]{"set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1)}, value);
        }
    }

    private static void writeNumberField(Object object, String fieldName, float value) {
        Field field = findField(object, fieldName);
        if (field == null) {
            return;
        }
        Class<?> type = field.getType();
        if (type == float.class || type == Float.class) {
            writeField(object, fieldName, value);
        } else if (type == double.class || type == Double.class) {
            writeField(object, fieldName, (double) value);
        }
    }

    private static boolean hasMethod(Object object, String name) {
        if (object == null) {
            return false;
        }
        for (Method method : object.getClass().getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasField(Object object, String name) {
        return object != null && findField(object, name) != null;
    }

    private static Object readField(Object object, String name) {
        Field field = findField(object, name);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeField(Object object, String name, Object value) {
        Field field = findField(object, name);
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(object, value);
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Object object, String name) {
        return object == null ? null : findField(object.getClass(), name);
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object invokeFirst(Object object, String[] names, Object... args) {
        for (String name : names) {
            Object result = invoke(object, name, args);
            if (result != null || hasCompatibleMethod(object, name, args)) {
                return result;
            }
        }
        return null;
    }

    private static Object invoke(Object object, String name, Object... args) {
        if (object == null) {
            return null;
        }
        for (Method method : object.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length
                    || !compatible(method.getParameterTypes(), args)) {
                continue;
            }
            try {
                return method.invoke(object, args);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasCompatibleMethod(Object object, String name, Object... args) {
        if (object == null) {
            return false;
        }
        for (Method method : object.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length
                    && compatible(method.getParameterTypes(), args)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compatible(Class<?>[] types, Object[] args) {
        for (int index = 0; index < types.length; index++) {
            if (args[index] == null) {
                if (types[index].isPrimitive()) {
                    return false;
                }
            } else if (!wrap(types[index]).isAssignableFrom(args[index].getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Object unwrapOptional(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static boolean hasType(Object object, String typeName) {
        try {
            Class<?> type = Class.forName(typeName, false, object.getClass().getClassLoader());
            return type.isInstance(object);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Iterable<?> valuesOf(Object value) {
        if (value == null) return List.of();
        if (value instanceof Map<?, ?> map) return map.values();
        if (value instanceof Iterable<?> iterable) return iterable;
        if (value instanceof Collection<?> collection) return collection;
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                result.add(Array.get(value, index));
            }
            return result;
        }
        return List.of();
    }

    private enum StrategyMode {
        ORDERS_ONLY,
        AUTONOMOUS_COMBAT,
        FREE_ROAM
    }

    private record CommandIntent(Object movementTarget, Vec3 facingTarget,
                                 boolean autonomousCombat, boolean freeRoam) {
    }
}
