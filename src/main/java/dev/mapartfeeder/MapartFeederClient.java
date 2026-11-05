package dev.mapartfeeder;

import dev.mapartfeeder.config.FeederConfig;
import dev.mapartfeeder.runtime.FeederController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 地图画供料：纯客户端 Fabric mod。
 *
 * <p>它不负责放置方块——放置交给投影打印机。它负责四件事：</p>
 * <ul>
 *   <li>登记原料容器；</li>
 *   <li>按 Litematica 材料清单缺口自动取料；</li>
 *   <li>走到地图画区域蛇形扫层，让打印机逐块放置；</li>
 *   <li>背包原料用完后回到地图画中心刷新清单，循环直到完成。</li>
 * </ul>
 */
public final class MapartFeederClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mapartfeeder");

    private static FeederConfig config;
    private static FeederController controller;

    private static KeyBinding markContainerKey;
    private static KeyBinding toggleKey;
    private static KeyBinding setCenterKey;
    private static KeyBinding clearCacheKey;

    @Override
    public void onInitializeClient() {
        config = FeederConfig.load(LOGGER);
        controller = new FeederController(MinecraftClient.getInstance(), config);

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("mapartfeeder", "controls"));
        markContainerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mapartfeeder.mark_container", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mapartfeeder.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        setCenterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mapartfeeder.set_center", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        clearCacheKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mapartfeeder.clear_cache", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));

        ClientTickEvents.END_CLIENT_TICK.register(MapartFeederClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (controller != null) controller.disconnect();
        });
        HudElementRegistry.addLast(Identifier.of("mapartfeeder", "hud"), MapartFeederClient::renderHud);
    }

    public static FeederController controller() {
        return controller;
    }

    public static FeederConfig config() {
        return config;
    }

    private static void tick(MinecraftClient client) {
        if (controller == null) return;
        while (markContainerKey.wasPressed()) controller.markContainer();
        while (toggleKey.wasPressed()) controller.toggle();
        while (setCenterKey.wasPressed()) controller.setCenter();
        while (clearCacheKey.wasPressed()) controller.clearCache();
        controller.tick();
    }

    private static void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (controller == null || client.textRenderer == null) return;
        if (!controller.isEnabled() && !controller.statusText().startsWith("没有更多")) return;
        String text = "地图画供料  " + controller.statusText();
        int width = client.textRenderer.getWidth(text);
        int x = 6;
        int y = 6;
        context.fill(x - 3, y - 3, x + width + 3, y + 10, 0xA0000000);
        context.drawTextWithShadow(client.textRenderer, Text.literal(text), x, y,
                controller.isEnabled() ? 0xFFFFFFFF : 0xFFFFAA55);
    }
}
