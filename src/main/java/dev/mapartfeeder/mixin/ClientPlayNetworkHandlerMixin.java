package dev.mapartfeeder.mixin;

import dev.mapartfeeder.runtime.InventorySyncTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 记录服务端发来的完整库存快照，作为“取料已确认”的唯一依据。
 * 目标与方法名使用 intermediary，配合 remap = false，产物不需要 refmap。
 */
@Mixin(targets = "net.minecraft.class_634", remap = false)
public abstract class ClientPlayNetworkHandlerMixin {
    /** onInventory */
    @Inject(method = "method_11153", at = @At("TAIL"))
    private void mapartfeeder$afterAuthoritativeContents(InventoryS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && (packet.syncId() == 0
                || packet.syncId() == client.player.currentScreenHandler.syncId)) {
            InventorySyncTracker.fullUpdate(packet.syncId(), packet.contents());
        }
    }
}
