package dev.mapartfeeder.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 只在磁盘上保存“用户资产”：原料容器坐标与地图画中心。
 * 运行开关、容器内容缓存都只存在内存里，重启即忘。
 */
public final class FeederConfig {
    /** 无感模式：自动打开的容器界面只在本机隐藏，服务端看到的仍是正常开箱。 */
    public boolean unobtrusiveInteraction = true;

    /** 打印阶段自动把投影打印机打开，离开打印阶段恢复用户原来的开关。 */
    public boolean autoTogglePrinter = true;

    /** 蛇形扫层的车道间距（格）。打印机交互距离约 4.5 格，6 格能覆盖整层。 */
    public int printLaneSpacing = 6;

    /** 中心点未填时使用的地图画半径；128×128 的半边长是 64。 */
    public int printAreaHalfSize = 64;

    /** 打印前/打印中多少 tick 检查一次材料。 */
    public int materialCheckIntervalTicks = 20;

    public int openTimeoutTicks = 40;
    public int syncTimeoutTicks = 80;
    public int clickIntervalTicks = 2;
    /** 没有取到东西的容器，再次尝试前的最短间隔。 */
    public int emptyContainerCooldownTicks = 200;
    /** 取完东西的容器，再次尝试前的最短间隔。 */
    public int visitedContainerCooldownTicks = 100;

    /** 请求 Litematica 重新统计材料后，最多等多久。 */
    public int recountTimeoutTicks = 300;

    public boolean chatNotifications = true;
    public boolean actionbarStatus = true;

    public List<ContainerRef> containers = new ArrayList<>();
    public ContainerRef center;

    public static FeederConfig load(Logger logger) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("mapartfeeder.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonObject tree = JsonParser.parseReader(reader).getAsJsonObject();
                FeederConfig config = gson.fromJson(tree, FeederConfig.class);
                if (config == null) throw new IllegalArgumentException("配置文件内容为空");
                if (!tree.has("unobtrusiveInteraction")) config.unobtrusiveInteraction = true;
                if (!tree.has("autoTogglePrinter")) config.autoTogglePrinter = true;
                config.validate();
                return config;
            } catch (IOException | RuntimeException e) {
                logger.error("配置读取失败，保留原文件并使用默认参数", e);
                return new FeederConfig();
            }
        }
        FeederConfig config = new FeederConfig();
        config.save(gson, path);
        return config;
    }

    public void save() {
        save(new GsonBuilder().setPrettyPrinting().create(),
                FabricLoader.getInstance().getConfigDir().resolve("mapartfeeder.json"));
    }

    private void save(Gson gson, Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 配置写不进去不应该影响游戏内行为。
        }
    }

    private void validate() {
        printLaneSpacing = clamp(printLaneSpacing, 2, 16);
        printAreaHalfSize = clamp(printAreaHalfSize, 1, 256);
        materialCheckIntervalTicks = clamp(materialCheckIntervalTicks, 5, 200);
        openTimeoutTicks = clamp(openTimeoutTicks, 10, 200);
        syncTimeoutTicks = clamp(syncTimeoutTicks, 20, 400);
        clickIntervalTicks = clamp(clickIntervalTicks, 1, 20);
        emptyContainerCooldownTicks = clamp(emptyContainerCooldownTicks, 20, 2400);
        visitedContainerCooldownTicks = clamp(visitedContainerCooldownTicks, 20, 2400);
        recountTimeoutTicks = clamp(recountTimeoutTicks, 20, 1200);
        if (containers == null) containers = new ArrayList<>();
        containers.removeIf(ref -> ref == null || ref.dimension == null || ref.dimension.isEmpty());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
