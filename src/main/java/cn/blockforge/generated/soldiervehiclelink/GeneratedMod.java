package cn.blockforge.generated.soldiervehiclelink;

import cn.blockforge.generated.soldiervehiclelink.event.RvpVehicleHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(GeneratedMod.MOD_ID)
public final class GeneratedMod {
    public static final String MOD_ID = "soldier_vehicle_link";

    public GeneratedMod() {
        MinecraftForge.EVENT_BUS.register(RvpVehicleHandler.class);
    }
}
