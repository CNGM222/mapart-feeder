package dev.mapartfeeder.runtime;

import dev.mapartfeeder.MapartFeederClient;
import dev.mapartfeeder.config.ContainerRef;
import dev.mapartfeeder.config.FeederConfig;
import dev.mapartfeeder.integration.LitematicaBridge;
import dev.mapartfeeder.integration.PrinterBridge;
import dev.mapartfeeder.logic.CursorTransfer;
import dev.mapartfeeder.logic.TransferPlanner;
import dev.mapartfeeder.nav.Nav;
import dev.mapartfeeder.nav.Walker;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端主线程状态机。整体流程：
 *
 * <ol>
 *   <li>读取当前 Litematica 投影的材料缺口；</li>
 *   <li>按缺口走到登记过的原料容器，用原版快速拿取补货；</li>
 *   <li>带着原料走到地图画区域，只朝“背包里有的、还没放置的方块”逐个移动，
 *       让投影打印机在交互距离内放置，而不是机械地扫整层；</li>
 *   <li>背包里能用的原料耗尽后，回到地图画中心（结构完整加载的位置）请求重新统计；</li>
 *   <li>再取料再打印，全自动循环，直到清单没有缺口，停在中心。</li>
 * </ol>
 *
 * <p>材料统计只在地图画中心请求：在原料容器旁边统计会因为结构未加载而得到错误数量。</p>
 *
 * <p>只使用原版交互：真实走路、真实开箱、真实 QUICK_MOVE 点击；不写世界、
 * 不发自造包、不改变服务端权限。</p>
 */
public final class FeederController {
    private enum Phase {
        IDLE, ANALYZE, TO_CONTAINER, OPENING, TRANSFER, SYNCING,
        TO_AREA, TO_PRINT, TO_CENTER, RECOUNT, DONE
    }

    private static final int MAX_CLICKS_PER_TICK = 128;

    private final MinecraftClient client;
    private final FeederConfig config;
    private final LitematicaBridge bridge = new LitematicaBridge();
    private final PrinterBridge printer = new PrinterBridge();
    private final ContainerCatalog catalog = new ContainerCatalog();
    private final Walker walker = new Walker();
    private final Map<String, Long> cooldowns = new HashMap<>();

    private boolean enabled;
    private Phase phase = Phase.IDLE;
    private long tick;
    private long nextThink;
    private String status = "已关闭";
    private String lastChat = "";
    private long lastChatTick = -1000;

    private LitematicaBridge.Context context;

    private ContainerRef target;
    private ScreenHandler ownedHandler;
    private boolean ownOpenRequested;
    private boolean hiddenSession;
    private Screen hiddenScreen;
    private boolean closingInternally;
    private long deadline;
    private long baseline;

    private final List<Taken> taken = new ArrayList<>();
    private boolean capacityLimited;
    private int clicksThisTick;

    private final List<ItemStack> skipped = new ArrayList<>();
    /** 当前扫描到的、背包里有对应物品且尚未放置的方块。 */
    private final List<BlockPos> targets = new ArrayList<>();
    private final Map<BlockPos, Integer> targetAttempts = new HashMap<>();
    private BlockPos currentTarget;
    private long nextTargetScan;
    private int printWaitTicks;
    private long missingBeforePrint = -1;
    private int noProgressCycles;
    private int areaFailures;

    private boolean recountRequested;
    private long recountBaseline;
    private long recountDeadline;

    private PrinterBridge.Snapshot printerSnapshot;
    private boolean printerManaged;

    private static final class Taken {
        final ItemStack sample;
        final int count;

        Taken(ItemStack sample, int count) {
            this.sample = sample.copyWithCount(1);
            this.count = count;
        }
    }

    public FeederController(MinecraftClient client, FeederConfig config) {
        this.client = client;
        this.config = config;
    }

    // ------------------------------------------------------------------ 用户操作

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        if (enabled) {
            stop("自动供料已关闭");
        } else {
            start();
        }
    }

    private void start() {
        if (config.center == null) {
            notice("请先用快捷键对准并指定地图画中心");
            return;
        }
        if (config.containers.isEmpty()) {
            notice("请先用快捷键登记至少一个原料容器");
            return;
        }
        if (client.player == null || client.world == null) return;
        enabled = true;
        phase = Phase.ANALYZE;
        tick = 0;
        nextThink = 0;
        skipped.clear();
        targets.clear();
        targetAttempts.clear();
        currentTarget = null;
        noProgressCycles = 0;
        missingBeforePrint = -1;
        recountRequested = false;
        printerManaged = false;
        printerSnapshot = null;
        InteractionGate.setBusy(false);
        notice("自动供料已开启：目标 " + config.center.shortText());
    }

    /** 关闭并恢复投影打印机原来的开关状态。 */
    public void stop(String reason) {
        enabled = false;
        phase = Phase.IDLE;
        walker.abort(client);
        closeOwnedQuietly();
        restorePrinter();
        InteractionGate.setBusy(false);
        target = null;
        notice(reason);
    }

    private void finish(String reason) {
        enabled = false;
        phase = Phase.DONE;
        walker.abort(client);
        closeOwnedQuietly();
        restorePrinter();
        InteractionGate.setBusy(false);
        target = null;
        notice(reason);
    }

    public void disconnect() {
        enabled = false;
        phase = Phase.IDLE;
        walker.abort(client);
        ownedHandler = null;
        target = null;
        context = null;
        catalog.clear();
        cooldowns.clear();
        targets.clear();
        targetAttempts.clear();
        currentTarget = null;
        skipped.clear();
        taken.clear();
        printerManaged = false;
        printerSnapshot = null;
        restoring = false;
        InteractionGate.setBusy(false);
        status = "已断开";
    }

    private boolean restoring;

    private void restorePrinter() {
        if (!printerManaged || restoring) return;
        restoring = true;
        try {
            printer.restore(printerSnapshot);
        } finally {
            printerManaged = false;
            printerSnapshot = null;
            restoring = false;
        }
    }

    public String statusText() {
        return status;
    }

    /** 登记/取消登记准星指向的容器。 */
    public void markContainer() {
        if (client.world == null || client.player == null) return;
        if (!(client.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
            notice("请先把准星对准原料容器");
            return;
        }
        BlockPos clicked = hit.getBlockPos();
        if (!ContainerBlocks.isContainer(client.world.getBlockState(clicked))) {
            notice("准星指向的不是箱子/木桶/潜影盒");
            return;
        }
        BlockPos pos = ContainerBlocks.canonical(client.world, clicked);
        ContainerRef ref = ContainerRef.of(client.world.getRegistryKey(), pos);
        boolean removed = config.containers.removeIf(existing -> existing.matches(client.world.getRegistryKey())
                && existing.pos().equals(pos));
        if (removed) {
            catalog.clear();
            config.save();
            notice("已取消登记容器 " + ref.shortText());
        } else {
            if (config.containers.size() >= 256) {
                notice("登记的容器过多（上限 256）");
                return;
            }
            config.containers.add(ref);
            config.save();
            notice("已登记原料容器 " + ref.shortText() + "（共 " + config.containers.size() + " 个）");
        }
    }

    /** 记录地图画中心：优先准星方块，否则取脚下方块。 */
    public void setCenter() {
        if (client.world == null || client.player == null) return;
        BlockPos pos;
        if (client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            pos = hit.getBlockPos().toImmutable();
        } else {
            pos = client.player.getBlockPos().down().toImmutable();
        }
        config.center = ContainerRef.of(client.world.getRegistryKey(), pos);
        config.save();
        targets.clear();
        targetAttempts.clear();
        currentTarget = null;
        notice("地图画中心已设为 " + config.center.shortText());
    }

    /** 清空本次会话的容器内容缓存，重新勘察。 */
    public void clearCache() {
        catalog.clear();
        cooldowns.clear();
        notice("已清空容器内容缓存，下次取料会重新勘察");
    }

    public boolean ownsCurrentScreen() {
        if (client.player == null) return false;
        return ownedHandler != null && client.currentScreen instanceof HandledScreen<?>
                && client.player.currentScreenHandler == ownedHandler;
    }

    /**
     * 只在本机取消“我们自己打开的那一次容器界面”。服务端会话与库存同步都不受影响。
     */
    public boolean interceptScreenChange(Screen requested) {
        if (closingInternally) return false;
        if (requested == null) {
            hiddenSession = false;
            hiddenScreen = null;
            return false;
        }
        if (client.player == null || client.world == null) return false;
        boolean hide = config.unobtrusiveInteraction
                && ownOpenRequested
                && requested instanceof HandledScreen<?>
                && supportedHandler(client.player.currentScreenHandler)
                && client.currentScreen == null;
        if (hide) {
            hiddenSession = true;
            hiddenScreen = requested;
            ownOpenRequested = false;
        }
        return hide;
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        tick++;
        if (client.world == null || client.player == null) {
            if (enabled) stop("世界已卸载，自动供料停止");
            return;
        }
        if (!enabled) return;
        if (client.currentScreen != null) {
            boolean containerFlow = (phase == Phase.OPENING || phase == Phase.TRANSFER
                    || phase == Phase.SYNCING) && client.currentScreen instanceof HandledScreen<?>;
            if (!containerFlow && !ownsCurrentScreen()) {
                pauseForScreen();
                return;
            }
        }
        if (client.player.isSpectator()) {
            finish("旁观模式无法供料");
            return;
        }
        try {
            step();
        } catch (RuntimeException | LinkageError error) {
            MapartFeederClient.LOGGER.error("自动供料已安全停止", error);
            finish("自动供料停止：" + LitematicaBridge.describeFailure(error));
        }
        showActionBar();
    }

    private void step() {
        switch (phase) {
            case IDLE -> {
                InteractionGate.setBusy(false);
                if (tick >= nextThink) phase = Phase.ANALYZE;
            }
            case ANALYZE -> analyze();
            case TO_CONTAINER -> travelToContainer();
            case OPENING -> waitForOpen();
            case TRANSFER -> transfer();
            case SYNCING -> waitForSync();
            case TO_AREA -> travelToArea();
            case TO_PRINT -> travelToPrint();
            case TO_CENTER -> travelToCenter();
            case RECOUNT -> recount();
            case DONE -> { }
        }
    }

    private void pauseForScreen() {
        if (phase != Phase.IDLE) {
            walker.abort(client);
            closeOwnedQuietly();
            phase = Phase.IDLE;
            nextThink = tick + 40;
            InteractionGate.setBusy(false);
            status = "检测到你打开了界面，自动供料暂停；关闭后自动继续";
        }
    }

    // ------------------------------------------------------------------ 分析

    private void analyze() {
        InteractionGate.setBusy(false);
        if (client.currentScreen != null) {
            pauseForScreen();
            return;
        }
        if (config.center == null) {
            status = "请先用快捷键指定地图画中心";
            return;
        }
        if (!config.center.sameDimension(client.world.getRegistryKey())) {
            status = "地图画中心不在当前维度";
            return;
        }
        if (registeredContainers().isEmpty()) {
            status = "请先用快捷键登记原料容器";
            return;
        }
        LitematicaBridge.Context selected = bridge.resolveContext();
        if (selected == null || !bridge.isPlacementEnabled(selected)) {
            context = null;
            status = "请先选中并启用一个 Litematica 投影";
            return;
        }
        if (context == null || context.placement() != selected.placement()
                || context.materialList() != selected.materialList()) {
            context = selected;
            skipped.clear();
            targets.clear();
            targetAttempts.clear();
            currentTarget = null;
            noProgressCycles = 0;
        }
        disablePrinterForTravel();

        // 材料统计只在中心做：在原料容器旁边统计时，地图画结构没有完整加载，数量会错。
        if (MaterialGenerationTracker.generation(context.materialList()) <= 0) {
            if (atCenter()) {
                phase = Phase.RECOUNT;
                recountRequested = false;
                status = "在中心统计材料清单";
            } else {
                phase = Phase.TO_AREA;
                status = "先去地图画中心，再统计材料";
            }
            return;
        }

        List<LitematicaBridge.Need> needs = bridge.needs(context, client.player);
        long missing = missingTotal(needs);
        if (missing == 0) {
            if (atCenter()) {
                finish("打印完成：材料清单已无缺口");
            } else {
                phase = Phase.TO_CENTER;
                status = "打印完成，回到中心";
            }
            return;
        }
        List<LitematicaBridge.Need> deficits = deficits(needs);
        if (deficits.isEmpty()) {
            beginPrint(missing);
            return;
        }

        boolean anyUnknown = catalog.hasUnknown(registeredContainers());
        List<ItemStack> impossible = new ArrayList<>();
        boolean anyObtainable = false;
        boolean anyCapacity = false;
        for (LitematicaBridge.Need need : deficits) {
            if (!(need.stack() instanceof ItemStack stack) || stack.isEmpty()) continue;
            if (catalog.hasKnownSource(stack)) anyObtainable = true;
            else if (anyUnknown) anyObtainable = true;
            else impossible.add(stack);
            if (InventorySlots.capacity(client.player.playerScreenHandler, client.player, stack) > 0) {
                anyCapacity = true;
            }
        }
        recordSkips(impossible);

        if (!anyCapacity || !anyObtainable) {
            if (impossible.size() == deficits.size() && skipped.size() > 0) {
                status = "缺口原料不在任何登记容器中，跳过并继续打印";
            }
            beginPrint(missing);
            return;
        }

        ContainerRef next = chooseContainer(deficits);
        if (next == null) {
            beginPrint(missing);
            return;
        }
        target = next;
        walker.resetRoute();
        phase = Phase.TO_CONTAINER;
        status = "前往原料容器 " + next.shortText();
    }

    private void beginPrint(long missing) {
        if (!printer.isAvailable()) {
            finish("未检测到投影打印机，无法开始打印");
            return;
        }
        if (bridge.schematicWorld() == null) {
            finish("读取不到投影的结构数据：请先选中投影并让它正常渲染");
            return;
        }
        missingBeforePrint = missing;
        recountRequested = false;
        currentTarget = null;
        targets.clear();
        targetAttempts.clear();
        nextTargetScan = tick;
        printWaitTicks = 0;
        InteractionGate.setBusy(false);
        phase = Phase.TO_PRINT;
        status = "原料就绪，前往地图画";
        enablePrinter();
    }

    private void recordSkips(List<ItemStack> impossible) {
        for (ItemStack stack : impossible) {
            boolean known = false;
            for (ItemStack existing : skipped) {
                if (InventorySlots.matches(existing, stack)) {
                    known = true;
                    break;
                }
            }
            if (known) continue;
            skipped.add(stack.copyWithCount(1));
            notice("跳过 " + stack.getName().getString() + "：所有登记容器中都没有");
        }
    }

    private List<LitematicaBridge.Need> deficits(List<LitematicaBridge.Need> needs) {
        List<LitematicaBridge.Need> result = new ArrayList<>();
        for (LitematicaBridge.Need need : needs) {
            if (need.deficit() > 0 && need.stack() instanceof ItemStack stack && !stack.isEmpty()) {
                result.add(need);
            }
        }
        return result;
    }

    private static long missingTotal(List<LitematicaBridge.Need> needs) {
        long total = 0;
        for (LitematicaBridge.Need need : needs) total += need.target();
        return total;
    }

    private ContainerRef chooseContainer(List<LitematicaBridge.Need> deficits) {
        Map<ContainerRef, Double> scores = new LinkedHashMap<>();
        for (LitematicaBridge.Need need : deficits) {
            if (!(need.stack() instanceof ItemStack stack) || stack.isEmpty()) continue;
            if (InventorySlots.capacity(client.player.playerScreenHandler, client.player, stack) <= 0) continue;
            for (ContainerRef ref : catalog.sourcesOf(stack)) {
                if (!usable(ref)) continue;
                double amount = Math.min(need.deficit(), catalog.knownCount(ref, stack));
                double score = amount * 64.0 / (distanceTo(ref.pos()) + 1.0);
                scores.merge(ref, score, Double::sum);
            }
        }
        if (!scores.isEmpty()) {
            ContainerRef best = null;
            double bestScore = -1;
            for (Map.Entry<ContainerRef, Double> entry : scores.entrySet()) {
                if (entry.getValue() > bestScore) {
                    bestScore = entry.getValue();
                    best = entry.getKey();
                }
            }
            return best;
        }
        // 还有没开过的登记容器：按距离优先勘察，才能判断“到底有没有这种原料”。
        ContainerRef nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ContainerRef ref : registeredContainers()) {
            if (catalog.isKnown(ref) || !usable(ref)) continue;
            double distance = distanceTo(ref.pos());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = ref;
            }
        }
        return nearest;
    }

    /** 当前维度、方块仍是容器、且不在冷却中的登记项。 */
    private List<ContainerRef> registeredContainers() {
        List<ContainerRef> result = new ArrayList<>();
        if (client.world == null) return result;
        for (ContainerRef ref : config.containers) {
            if (!ref.sameDimension(client.world.getRegistryKey())) continue;
            if (!client.world.isChunkLoaded(ref.pos())) continue;
            if (!ContainerBlocks.isContainer(client.world.getBlockState(ref.pos()))) continue;
            result.add(ref);
        }
        return result;
    }

    private boolean usable(ContainerRef ref) {
        if (client.world == null || !ref.sameDimension(client.world.getRegistryKey())) return false;
        if (!client.world.isChunkLoaded(ref.pos())) return false;
        if (!ContainerBlocks.isContainer(client.world.getBlockState(ref.pos()))) return false;
        Long until = cooldowns.get(ref.cacheKey());
        return until == null || until <= tick;
    }

    private double distanceTo(BlockPos pos) {
        Vec3d feet = client.player.getEntityPos();
        return Math.sqrt(feet.squaredDistanceTo(Vec3d.ofCenter(pos)));
    }

    // ------------------------------------------------------------------ 容器

    private void travelToContainer() {
        if (target == null) {
            phase = Phase.ANALYZE;
            return;
        }
        if (!usable(target)) {
            phase = Phase.ANALYZE;
            return;
        }
        InteractionGate.setBusy(true);
        Nav.Goal goal = new Nav.ContainerGoal(target.pos());
        Walker.Result result = walker.tick(client, goal);
        if (result == Walker.Result.FAILED) {
            cooldowns.put(target.cacheKey(), tick + config.emptyContainerCooldownTicks);
            notice("走不到容器 " + target.shortText() + "，先跳过");
            target = null;
            phase = Phase.ANALYZE;
        } else if (result == Walker.Result.ARRIVED) {
            beginOpen();
        }
    }

    private void beginOpen() {
        InteractionGate.setBusy(true);
        BlockHitResult hit = Nav.hitContainer(client, target.pos());
        Hand hand = openingHand();
        if (hit == null || hand == null) {
            cooldowns.put(target.cacheKey(), tick + config.emptyContainerCooldownTicks);
            target = null;
            phase = Phase.ANALYZE;
            status = "容器此刻无法正常打开";
            return;
        }
        ownedHandler = null;
        ownOpenRequested = true;
        hiddenSession = false;
        baseline = InventorySyncTracker.sequence();
        deadline = tick + config.openTimeoutTicks;
        phase = Phase.OPENING;
        status = "打开原料容器 " + target.shortText();
        client.interactionManager.interactBlock(client.player, hand, hit);
    }

    private Hand openingHand() {
        if (client.player.isSneaking() || client.player.isUsingItem()) return null;
        return Hand.MAIN_HAND;
    }

    private boolean supportedHandler(ScreenHandler handler) {
        return handler != null && (handler.getClass() == GenericContainerScreenHandler.class
                || handler.getClass() == ShulkerBoxScreenHandler.class);
    }

    private void waitForOpen() {
        ScreenHandler current = client.player.currentScreenHandler;
        if (current == client.player.playerScreenHandler) {
            if (tick > deadline) {
                failContainer("开箱超时");
            }
            return;
        }
        if (ownedHandler == null) {
            if (!supportedHandler(current) || !(ownOpenRequested || hiddenSession)) {
                pauseForScreen();
                return;
            }
            ownedHandler = current;
        }
        if (!InventorySyncTracker.hasFullUpdateAfter(ownedHandler.syncId, baseline)) {
            if (tick > deadline) failContainer("等待完整库存同步超时");
            return;
        }
        if (!ownedHandler.getCursorStack().isEmpty()) {
            pauseForScreen();
            notice("同步后鼠标上仍有物品，已暂停");
            return;
        }
        onContainerReady();
    }

    private void onContainerReady() {
        catalog.record(target, containerStacks(ownedHandler));
        List<LitematicaBridge.Need> needs = bridge.needs(context, client.player);
        boolean useful = false;
        for (LitematicaBridge.Need need : needs) {
            if (need.deficit() <= 0 || !(need.stack() instanceof ItemStack stack)) continue;
            if (InventorySlots.countContainer(ownedHandler, client.player, stack) > 0
                    && InventorySlots.capacity(ownedHandler, client.player, stack) > 0) {
                useful = true;
                break;
            }
        }
        if (!useful) {
            finishContainer("该容器没有需要补的原料", config.emptyContainerCooldownTicks);
            return;
        }
        taken.clear();
        capacityLimited = false;
        phase = Phase.TRANSFER;
        status = "从 " + target.shortText() + " 快速取料";
    }

    private void transfer() {
        if (!ownedHandler.getCursorStack().isEmpty()) {
            pauseForScreen();
            return;
        }
        clicksThisTick = 0;
        List<LitematicaBridge.Need> needs = bridge.needs(context, client.player);
        for (int index = 0; index < ownedHandler.slots.size(); index++) {
            Slot slot = ownedHandler.getSlot(index);
            if (InventorySlots.isPlayerSlot(slot, client.player) || !slot.canTakeItems(client.player)) continue;
            ItemStack source = slot.getStack();
            if (source.isEmpty()) continue;
            long deficit = deficitFor(needs, source);
            if (deficit <= 0) continue;
            int capacity = InventorySlots.capacity(ownedHandler, client.player, source);
            TransferPlanner.Plan plan = TransferPlanner.plan(source.getCount(), deficit, capacity);
            if (plan.kind() == TransferPlanner.Kind.NONE) {
                capacityLimited = true;
                continue;
            }
            int cost = plan.kind() == TransferPlanner.Kind.QUICK_MOVE ? 1
                    : 38 + Math.min(plan.transferCount(), plan.pickupCount() - plan.transferCount());
            if (clicksThisTick + cost + 2 > MAX_CLICKS_PER_TICK) {
                break;
            }
            if (!performTransfer(slot, plan)) {
                failContainer("取料预测发生变化");
                return;
            }
            // 客户端库存已经变化：重算一次缺口，避免同种物品的第二堆又按旧缺口全取。
            needs = bridge.needs(context, client.player);
            if (!slot.getStack().isEmpty()) index--;
        }
        if (taken.isEmpty()) {
            finishContainer(capacityLimited ? "背包容量不足，先不取料" : "该容器没有可取的原料",
                    capacityLimited ? config.visitedContainerCooldownTicks : config.emptyContainerCooldownTicks);
            return;
        }
        requestSync();
    }

    private long deficitFor(List<LitematicaBridge.Need> needs, ItemStack source) {
        long result = 0;
        for (LitematicaBridge.Need need : needs) {
            if (need.stack() instanceof ItemStack stack && InventorySlots.matches(stack, source)) {
                result = Math.max(result, need.deficit());
            }
        }
        return result;
    }

    private boolean performTransfer(Slot slot, TransferPlanner.Plan plan) {
        ItemStack item = slot.getStack().copy();
        long before = InventorySlots.countDirect(client.player, item);
        long containerBefore = InventorySlots.countContainer(ownedHandler, client.player, item);
        if (plan.kind() == TransferPlanner.Kind.QUICK_MOVE) {
            click(slot.id, 0, SlotActionType.QUICK_MOVE);
        } else {
            executeExact(slot, item, plan);
        }
        long moved = InventorySlots.countDirect(client.player, item) - before;
        long removed = containerBefore - InventorySlots.countContainer(ownedHandler, client.player, item);
        if (moved != plan.transferCount() || removed != moved || !ownedHandler.getCursorStack().isEmpty()) {
            return false;
        }
        catalog.subtract(target, item, (int) moved);
        recordTaken(item, (int) moved);
        return true;
    }

    private void executeExact(Slot sourceSlot, ItemStack item, TransferPlanner.Plan plan) {
        CursorTransfer.execute(plan, new CursorTransfer.Access() {
            @Override public void pickup(boolean half) {
                click(sourceSlot.id, half ? 1 : 0, SlotActionType.PICKUP);
            }

            @Override public int cursorCount() {
                ItemStack cursor = ownedHandler.getCursorStack();
                return cursor.isEmpty() ? 0 : InventorySlots.matches(cursor, item) ? cursor.getCount() : -1;
            }

            @Override public boolean canReturn(int count) {
                return InventorySlots.space(sourceSlot, item) >= count;
            }

            @Override public void returnToSource(boolean single) {
                click(sourceSlot.id, single ? 1 : 0, SlotActionType.PICKUP);
            }

            @Override public int destination() {
                Slot slot = InventorySlots.destination(ownedHandler, client.player, item);
                return slot == null ? -1 : slot.id;
            }

            @Override public int space(int slot) {
                return InventorySlots.space(ownedHandler.getSlot(slot), item);
            }

            @Override public void deposit(int slot, boolean single) {
                click(slot, single ? 1 : 0, SlotActionType.PICKUP);
            }
        });
    }

    private void click(int slot, int button, SlotActionType type) {
        if (ownedHandler == null || client.player.currentScreenHandler != ownedHandler) {
            throw new IllegalStateException("容器会话已失效");
        }
        client.interactionManager.clickSlot(ownedHandler.syncId, slot, button, type, client.player);
        clicksThisTick++;
    }

    private void recordTaken(ItemStack sample, int count) {
        for (int i = 0; i < taken.size(); i++) {
            Taken entry = taken.get(i);
            if (InventorySlots.matches(entry.sample, sample)) {
                taken.set(i, new Taken(entry.sample, entry.count + count));
                return;
            }
        }
        taken.add(new Taken(sample, count));
    }

    private void requestSync() {
        if (client.getNetworkHandler() == null) {
            failContainer("网络连接已断开");
            return;
        }
        phase = Phase.SYNCING;
        baseline = InventorySyncTracker.sequence();
        deadline = tick + config.syncTimeoutTicks;
        status = "结算本次取料";
        client.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(
                ownedHandler.syncId, -1, (short) -1, (byte) 0, SlotActionType.PICKUP,
                Int2ObjectMaps.emptyMap(), ItemStackHash.EMPTY));
    }

    private void waitForSync() {
        InventorySyncTracker.Snapshot snapshot = InventorySyncTracker.latest(ownedHandler.syncId);
        if (snapshot != null && snapshot.sequence() > baseline) {
            rebuildCatalog(snapshot);
            closeContainer(config.visitedContainerCooldownTicks);
        } else if (tick > deadline) {
            closeContainer(config.visitedContainerCooldownTicks);
        }
    }

    private void rebuildCatalog(InventorySyncTracker.Snapshot snapshot) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Slot slot : ownedHandler.slots) {
            if (InventorySlots.isPlayerSlot(slot, client.player)) continue;
            int id = slot.id;
            if (id >= 0 && id < snapshot.contents().size()) {
                ItemStack stack = snapshot.contents().get(id);
                if (!stack.isEmpty()) stacks.add(stack);
            }
        }
        catalog.record(target, stacks);
    }

    private void finishContainer(String reason, long cooldownTicks) {
        if (target != null) cooldowns.put(target.cacheKey(), tick + cooldownTicks);
        closeContainer(0);
        notice(reason);
    }

    private void failContainer(String reason) {
        finishContainer(reason, config.emptyContainerCooldownTicks);
    }

    private void closeContainer(long cooldownTicks) {
        if (ownedHandler != null && client.player != null
                && client.player.currentScreenHandler == ownedHandler) {
            if (!ownedHandler.getCursorStack().isEmpty()) {
                pauseForScreen();
                notice("鼠标上还有物品，已暂停并保留界面");
                return;
            }
            closingInternally = true;
            try {
                client.player.closeHandledScreen();
            } finally {
                closingInternally = false;
            }
        }
        if (target != null && cooldownTicks > 0) {
            cooldowns.put(target.cacheKey(), tick + cooldownTicks);
        }
        ownedHandler = null;
        ownOpenRequested = false;
        hiddenSession = false;
        hiddenScreen = null;
        target = null;
        taken.clear();
        capacityLimited = false;
        InteractionGate.setBusy(false);
        phase = Phase.ANALYZE;
        nextThink = tick + 2;
    }

    private void closeOwnedQuietly() {
        hiddenSession = false;
        hiddenScreen = null;
        ownOpenRequested = false;
        if (ownedHandler != null && client.player != null
                && client.player.currentScreenHandler == ownedHandler) {
            if (ownedHandler.getCursorStack().isEmpty()) {
                closingInternally = true;
                try {
                    client.player.closeHandledScreen();
                } catch (RuntimeException ignored) {
                    // 连接已断开时无需继续清理。
                } finally {
                    closingInternally = false;
                }
            }
        }
        ownedHandler = null;
    }

    private List<ItemStack> containerStacks(ScreenHandler handler) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (InventorySlots.isPlayerSlot(slot, client.player)) continue;
            if (!slot.getStack().isEmpty()) stacks.add(slot.getStack().copy());
        }
        return stacks;
    }

    // ------------------------------------------------------------------ 打印

    /**
     * 打印阶段：只走向“背包里有对应物品、且结构里还没放置”的方块。
     * 打印机在交互距离内自动放置，所以到目标附近停留一会儿再重新扫描；
     * 已经放好的方块会从目标里消失，不再机械地扫整层。
     */
    private void travelToPrint() {
        if (!printer.isAvailable()) {
            finish("未检测到投影打印机，无法开始打印");
            return;
        }
        InteractionGate.setBusy(false);
        // 打印机可能被它自己的自动关闭逻辑关掉，打印阶段持续校正。
        if (tick % 20 == 0 && !printer.isPrintEnabled()) enablePrinter();

        if (printWaitTicks > 0) {
            printWaitTicks--;
            if (printWaitTicks == 0) {
                if (currentTarget != null && stillMissing(currentTarget)) {
                    // 站了一会儿仍然没放上：记一次失败，换下一个目标，稍后还会回来。
                    targetAttempts.merge(currentTarget, 1, Integer::sum);
                }
                currentTarget = null;
                nextTargetScan = tick;
            }
            return;
        }

        if (tick >= nextTargetScan) {
            nextTargetScan = tick + 20;
            scanTargets();
            if (currentTarget != null && !stillMissing(currentTarget)) currentTarget = null;
            if (targets.isEmpty()) {
                afterTargetsExhausted();
                return;
            }
            if (currentTarget == null) currentTarget = chooseTarget();
            if (currentTarget == null) {
                afterTargetsExhausted();
                return;
            }
        }

        if (currentTarget == null) {
            afterTargetsExhausted();
            return;
        }
        if (!stillMissing(currentTarget)) {
            currentTarget = null;
            nextTargetScan = tick;
            return;
        }

        // 站在离目标约两格的位置，避免直接站在待放置方块上导致打印机放不进去；
        // 第一次走不到时再改成直接走到方块坐标。
        int attempts = targetAttempts.getOrDefault(currentTarget, 0);
        int offset = attempts == 0 ? 2 : 0;
        int dx = Integer.compare(client.player.getBlockX(), currentTarget.getX());
        int dz = Integer.compare(client.player.getBlockZ(), currentTarget.getZ());
        if (dx == 0 && dz == 0) dx = 1;
        int goalX = currentTarget.getX() + dx * offset;
        int goalZ = currentTarget.getZ() + dz * offset;
        Nav.PointGoal goal = new Nav.PointGoal(goalX, goalZ,
                config.center.y - 2, config.center.y + 3, offset == 0 ? 1.2 : 1.0);
        Walker.Result result = walker.tick(client, goal);
        if (result == Walker.Result.ARRIVED) {
            printWaitTicks = config.printWaitTicks;
            status = "等待打印机放置 " + currentTarget.toShortString();
        } else if (result == Walker.Result.FAILED) {
            targetAttempts.merge(currentTarget, 1, Integer::sum);
            currentTarget = null;
            nextTargetScan = tick;
            status = "这个打印点走不到，换下一个";
        } else {
            status = "前往未放置方块 " + currentTarget.toShortString();
        }
    }

    /** 只走到中心，不触发材料统计；用于结构区块未加载、需要靠近后再扫描的情况。 */
    private void travelToArea() {
        InteractionGate.setBusy(false);
        if (config.center == null) {
            phase = Phase.ANALYZE;
            return;
        }
        Nav.PointGoal goal = new Nav.PointGoal(config.center.x, config.center.z,
                config.center.y - 2, config.center.y + 4, 1.5);
        Walker.Result result = walker.tick(client, goal);
        if (result == Walker.Result.RUNNING) {
            status = "前往地图画中心";
            return;
        }
        if (result == Walker.Result.FAILED && ++areaFailures >= 3) {
            finish("走不到地图画中心，无法继续统计与打印");
            return;
        }
        if (result == Walker.Result.ARRIVED) areaFailures = 0;
        phase = Phase.ANALYZE;
    }

    /** 扫描地图画区域，收集“结构要求、现实没有、背包里还有”的方块位置。 */
    private void scanTargets() {
        targets.clear();
        Object schematic = bridge.schematicWorld();
        if (schematic == null || config.center == null) return;
        int half = config.printAreaHalfSize;
        int minX = config.center.x - half;
        int maxX = config.center.x + half - 1;
        int minZ = config.center.z - half;
        int maxZ = config.center.z + half - 1;
        int y = config.center.y;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                cursor.set(x, y, z);
                BlockState required = bridge.stateInSchematic(schematic, cursor);
                if (required == null || required.isAir()) continue;
                if (required == client.world.getBlockState(cursor)) continue;
                Item item = required.getBlock().asItem();
                if (item == Items.AIR) continue;
                if (InventorySlots.countDirect(client.player, new ItemStack(item)) <= 0) continue;
                targets.add(new BlockPos(x, y, z));
            }
        }
    }

    private BlockPos chooseTarget() {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3d feet = client.player.getEntityPos();
        for (BlockPos pos : targets) {
            int attempts = targetAttempts.getOrDefault(pos, 0);
            if (attempts >= 3) continue;
            if (pos.equals(client.player.getBlockPos())) continue;
            double distance = feet.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private boolean stillMissing(BlockPos pos) {
        Object schematic = bridge.schematicWorld();
        if (schematic == null) return false;
        BlockState required = bridge.stateInSchematic(schematic, pos);
        if (required == null || required.isAir()) return false;
        return required != client.world.getBlockState(pos);
    }

    /** 当前范围内已经没有“能放置”的目标：判断是缺料还是结构没加载。 */
    private void afterTargetsExhausted() {
        List<LitematicaBridge.Need> needs = bridge.needs(context, client.player);
        long missing = missingTotal(needs);
        if (missing == 0) {
            phase = Phase.TO_CENTER;
            status = "没有剩余方块，回中心确认";
            return;
        }
        if (hasUsefulMaterial()) {
            if (atCenter()) {
                phase = Phase.TO_CENTER;
                status = "中心也没有可放置目标，重新统计材料";
            } else {
                phase = Phase.TO_AREA;
                status = "回到中心继续寻找未放置方块";
            }
            return;
        }
        phase = Phase.TO_CENTER;
        status = "背包原料用完，回中心刷新清单";
    }

    private void travelToCenter() {
        InteractionGate.setBusy(false);
        if (tick % 20 == 0) disablePrinterForTravel();
        if (config.center == null) {
            phase = Phase.RECOUNT;
            return;
        }
        Nav.PointGoal goal = new Nav.PointGoal(config.center.x, config.center.z,
                config.center.y - 2, config.center.y + 4, 1.5);
        Walker.Result result = walker.tick(client, goal);
        if (result == Walker.Result.RUNNING) {
            status = "回到地图画中心";
            return;
        }
        phase = Phase.RECOUNT;
        recountRequested = false;
    }

    private void recount() {
        if (context == null || !bridge.isStillSelected(context)) {
            finish("投影已被取消或切换，停止自动供料");
            return;
        }
        if (!recountRequested) {
            recountBaseline = MaterialGenerationTracker.generation(context.materialList());
            bridge.requestRecount(context);
            printer.requestMaterialRefresh();
            recountDeadline = tick + config.recountTimeoutTicks;
            recountRequested = true;
            status = "在中心请求刷新材料清单";
            return;
        }
        long generation = MaterialGenerationTracker.generation(context.materialList());
        if (generation <= recountBaseline && tick < recountDeadline) return;

        List<LitematicaBridge.Need> needs = bridge.needs(context, client.player);
        long missing = missingTotal(needs);
        if (missing == 0) {
            finish("打印完成：材料清单已无缺口");
            return;
        }
        if (missingBeforePrint >= 0 && missing >= missingBeforePrint) {
            noProgressCycles++;
        } else {
            noProgressCycles = 0;
        }
        List<LitematicaBridge.Need> deficits = deficits(needs);
        if (noProgressCycles >= 2 || (!hasUsefulMaterial() && chooseContainer(deficits) == null)) {
            String extra = skipped.isEmpty() ? ""
                    : "；已跳过 " + skipped.size() + " 种容器里没有的原料";
            finish("没有更多可打印的进度，停止在中心（剩余 " + missing + " 块）" + extra);
            return;
        }
        targets.clear();
        targetAttempts.clear();
        currentTarget = null;
        nextTargetScan = tick;
        phase = Phase.ANALYZE;
        nextThink = tick + 2;
        status = "重新分析材料缺口";
    }

    /** 背包里是否还有任何“清单缺口对应”的原料。 */
    private boolean hasUsefulMaterial() {
        try {
            List<LitematicaBridge.Need> needs = bridge.needs(context, client.player);
            for (LitematicaBridge.Need need : needs) {
                if (need.deficit() <= 0 || !(need.stack() instanceof ItemStack stack)) continue;
                if (InventorySlots.countDirect(client.player, stack) > 0) return true;
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private boolean atCenter() {
        if (config.center == null || client.player == null) return false;
        double dx = client.player.getX() - (config.center.x + 0.5);
        double dz = client.player.getZ() - (config.center.z + 0.5);
        return dx * dx + dz * dz <= 4.0 && Math.abs(client.player.getY() - config.center.y) <= 4;
    }

    // ------------------------------------------------------------------ 打印机

    private void enablePrinter() {
        if (!config.autoTogglePrinter || !printer.isAvailable()) return;
        if (!printerManaged) {
            printerSnapshot = printer.capture();
            printerManaged = true;
        }
        if (!printer.isPrintEnabled()) printer.enablePrinting();
    }

    private void disablePrinterForTravel() {
        if (!config.autoTogglePrinter || !printer.isAvailable()) return;
        if (!printerManaged) {
            printerSnapshot = printer.capture();
            printerManaged = true;
        }
        if (printer.isPrintEnabled()) {
            // 只关打印模块，不动用户其它模块的总开关；总开关在打印阶段才需要。
            printer.disablePrinting();
        }
    }

    // ------------------------------------------------------------------ 提示

    private void showActionBar() {
        if (!config.actionbarStatus || client.player == null) return;
        if (tick % 40 != 0) return;
        client.player.sendMessage(Text.literal("[自动供料] " + status), true);
    }

    private void notice(String text) {
        status = text;
        if (config.chatNotifications && client.player != null
                && (!text.equals(lastChat) || tick - lastChatTick > 200)) {
            client.player.sendMessage(Text.literal("[自动供料] " + text), false);
            lastChat = text;
            lastChatTick = tick;
        }
    }
}
