package cn.blockforge.generated.soldiervehiclelink.client;

import cn.blockforge.generated.soldiervehiclelink.compat.RvpVehicleBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 让 RVP 载具乘员像原版乘客一样显示自定义名称。 */
@Mod.EventBusSubscriber(
        modid = "soldier_vehicle_link",
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class PassengerNameTagHandler {
    private PassengerNameTagHandler() {
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living)
                || !living.hasCustomName()
                || !living.isPassenger()
                || !RvpVehicleBridge.isVehicle(living.getVehicle())) {
            return;
        }

        // ALLOW bypasses the renderer's normal passenger/name visibility fallback.
        event.setResult(Event.Result.ALLOW);
    }
}
