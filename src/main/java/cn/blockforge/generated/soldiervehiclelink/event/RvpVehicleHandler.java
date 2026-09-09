package cn.blockforge.generated.soldiervehiclelink.event;

import cn.blockforge.generated.soldiervehiclelink.compat.RvpVehicleBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 在服务端把任意生物的原生 AI 接到载具端口，保证移动和攻击由单位 AI 驱动。 */
public final class RvpVehicleHandler {
    private RvpVehicleHandler() {
    }

    @SubscribeEvent
    public static void onEntityMount(EntityMountEvent event) {
        if (!(event.getEntityMounting() instanceof LivingEntity unit)) {
            return;
        }
        Entity vehicle = event.getEntityBeingMounted();
        if (!RvpVehicleBridge.isControllableUnit(unit)
                || !RvpVehicleBridge.isVehicle(vehicle)) {
            return;
        }
        if (event.isMounting()) {
            // 兼容单位自己的骑乘动作：标准乘客关系建立后再登记驾驶位。
            RvpVehicleBridge.adoptMountedOperator(unit, vehicle);
        } else if (event.isDismounting()) {
            RvpVehicleBridge.releaseMountedOperator(unit, vehicle);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 在实体 tick 之前写入持续输入，避免车辆上一 tick 的输入被延迟一拍。
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity unit)
                        || !RvpVehicleBridge.isControllableUnit(entity)
                        || RvpVehicleBridge.isVehicle(entity)) {
                    continue;
                }
                Entity vehicle = unit.getVehicle();
                if (RvpVehicleBridge.isVehicleOperator(unit, vehicle)) {
                    RvpVehicleBridge.tickOperator(unit, vehicle);
                } else if (unit.isPassenger() && RvpVehicleBridge.isVehicle(vehicle)) {
                    // 标准骑乘关系已建立时，只确认真实驾驶位，不重排普通乘员座位。
                    RvpVehicleBridge.adoptMountedOperator(unit, vehicle);
                    RvpVehicleBridge.tickCrewMember(unit, vehicle);
                }
            }
        }
    }
}
