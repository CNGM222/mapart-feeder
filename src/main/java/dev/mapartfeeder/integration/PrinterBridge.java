package dev.mapartfeeder.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 对投影打印机的反射适配层，同时兼容两种常见发行版：
 *
 * <ul>
 *   <li>原版 aleksilassila 结构：{@code Configs.PRINT_MODE} 是打印开关；</li>
 *   <li>BiliXWhite 分支：{@code Configs.Core.WORK_SWITCH} 是总开关，
 *       {@code Configs.Print.ENABLED} 是打印模块开关。</li>
 * </ul>
 *
 * <p>只读写开关与“缺失材料”缓存，不写它的配置文件、不改其它模块。所有调用必须在
 * 客户端主线程。</p>
 */
public final class PrinterBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("mapartfeeder");
    private static final String PREFIX = "me.aleksilassila.litematica.printer.";

    public record Snapshot(boolean workSwitch, boolean printEnabled) { }

    private Object primary;
    private Object secondary;
    private Method booleanGetter;
    private Method booleanSetter;
    private Method trackerGetInstance;
    private Method trackerReset;
    private boolean initialized;
    private String failure = "";
    private String variant = "";

    public boolean isAvailable() {
        ensureInit();
        return primary != null;
    }

    public String variant() {
        ensureInit();
        return variant;
    }

    public String failureReason() {
        ensureInit();
        return failure;
    }

    private void ensureInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> configs = load(PREFIX + "config.Configs");
            Class<?> forkCore = tryLoad(PREFIX + "config.Configs$Core");
            Class<?> forkPrint = tryLoad(PREFIX + "config.Configs$Print");
            if (forkCore != null && forkPrint != null
                    && staticField(forkCore, "WORK_SWITCH") != null
                    && staticField(forkPrint, "ENABLED") != null) {
                primary = staticField(forkCore, "WORK_SWITCH");
                secondary = staticField(forkPrint, "ENABLED");
                variant = "fork";
            } else {
                primary = staticField(configs, "PRINT_MODE");
                variant = primary != null ? "original" : "";
            }
            if (primary == null) throw new IllegalStateException("找不到打印开关配置");
            booleanGetter = primary.getClass().getMethod("getBooleanValue");
            booleanSetter = primary.getClass().getMethod("setBooleanValue", boolean.class);
            if (trackerClass() != null) {
                Class<?> tracker = trackerClass();
                trackerGetInstance = tracker.getMethod("getInstance");
                trackerReset = tracker.getMethod("reset");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failure = describe(e);
            primary = null;
            secondary = null;
            LOGGER.warn("未找到可用的投影打印机接口：{}", failure);
        }
    }

    private static Class<?> trackerClass() {
        try {
            return Class.forName(PREFIX + "printer.MissingMaterialTracker", false,
                    PrinterBridge.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    public Snapshot capture() {
        ensureInit();
        if (primary == null) return new Snapshot(false, true);
        return new Snapshot(read(primary), secondary == null || read(secondary));
    }

    public void restore(Snapshot snapshot) {
        ensureInit();
        if (primary == null || snapshot == null) return;
        write(primary, snapshot.workSwitch());
        if (secondary != null) write(secondary, snapshot.printEnabled());
    }

    public boolean isPrintEnabled() {
        ensureInit();
        if (primary == null) return false;
        return secondary == null ? read(primary) : read(secondary);
    }

    public boolean isWorkSwitchOn() {
        ensureInit();
        if (primary == null) return false;
        return read(primary);
    }

    public boolean enablePrinting() {
        ensureInit();
        if (primary == null) return false;
        write(primary, true);
        if (secondary != null) write(secondary, true);
        return true;
    }

    /** 只关打印：原版直接关 PRINT_MODE，分支只关打印模块。 */
    public void disablePrinting() {
        ensureInit();
        if (primary == null) return;
        if (secondary != null) {
            write(secondary, false);
        } else {
            write(primary, false);
        }
    }

    /** 清空打印机的缺失材料记录（仅部分分支提供），让下一轮重新统计。 */
    public void requestMaterialRefresh() {
        ensureInit();
        if (trackerGetInstance == null || trackerReset == null) return;
        try {
            Object tracker = trackerGetInstance.invoke(null);
            trackerReset.invoke(tracker);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("请求打印机刷新缺失材料失败：{}", describe(e));
        }
    }

    private boolean read(Object config) {
        try {
            Object value = booleanGetter.invoke(config);
            return value instanceof Boolean b && b;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private void write(Object config, boolean value) {
        try {
            booleanSetter.invoke(config, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("写入打印机开关失败：{}", describe(e));
        }
    }

    private static Object staticField(Class<?> owner, String name) {
        try {
            Field field = owner.getField(name);
            if (!Modifier.isStatic(field.getModifiers())) return null;
            field.trySetAccessible();
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, PrinterBridge.class.getClassLoader());
    }

    private static Class<?> tryLoad(String name) {
        try {
            return load(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable next = error; next != null; next = next.getCause()) {
            if (!text.isEmpty()) text.append("；");
            text.append(next.getClass().getSimpleName()).append("：").append(next.getMessage());
        }
        return text.toString();
    }
}
