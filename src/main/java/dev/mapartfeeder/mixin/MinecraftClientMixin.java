package dev.mapartfeeder.mixin;

import dev.mapartfeeder.MapartFeederClient;
import dev.mapartfeeder.runtime.FeederController;
import dev.mapartfeeder.runtime.InteractionGate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 目标类与方法名直接写 intermediary 名字并关闭 remap：产物不再需要 refmap，
 * 离线构建也能得到运行时正确的注入点。
 */
@Mixin(targets = "net.minecraft.class_310", remap = false)
public abstract class MinecraftClientMixin {
    /** doItemUse */
    @Inject(method = "method_1583", at = @At("HEAD"), cancellable = true)
    private void mapartfeeder$pauseItemUse(CallbackInfo ci) {
        if (InteractionGate.isBusy()) ci.cancel();
    }

    /** doAttack */
    @Inject(method = "method_1536", at = @At("HEAD"), cancellable = true)
    private void mapartfeeder$pauseAttack(CallbackInfoReturnable<Boolean> cir) {
        if (InteractionGate.isBusy()) cir.setReturnValue(false);
    }

    /** setScreen */
    @Inject(method = "method_1507", at = @At("HEAD"), cancellable = true)
    private void mapartfeeder$hideOwnContainerScreen(Screen screen, CallbackInfo ci) {
        FeederController controller = MapartFeederClient.controller();
        if (controller != null && controller.interceptScreenChange(screen)) ci.cancel();
    }
}
