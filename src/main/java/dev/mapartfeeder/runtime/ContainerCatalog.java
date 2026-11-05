package dev.mapartfeeder.runtime;

import dev.mapartfeeder.config.ContainerRef;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本次游戏会话内的容器内容缓存。
 *
 * <p>只有真正打开过、并拿到服务端完整库存的容器才会写入这里；缓存条目按
 * “物品 + 组件”精确匹配。玩家自己翻箱子、或外部原因改变容器内容时，缓存会
 * 变旧，所以提供清空缓存（重新勘察）的快捷键。</p>
 */
public final class ContainerCatalog {
    public static final class Count {
        public final ItemStack sample;
        public int count;

        Count(ItemStack sample, int count) {
            this.sample = sample.copyWithCount(1);
            this.count = count;
        }
    }

    private static final class Entry {
        final ContainerRef ref;
        final List<Count> counts = new ArrayList<>();

        Entry(ContainerRef ref) {
            this.ref = ref;
        }

        Count find(ItemStack stack) {
            for (Count count : counts) {
                if (InventorySlots.matches(count.sample, stack)) return count;
            }
            return null;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public void clear() {
        entries.clear();
    }

    public boolean isKnown(ContainerRef ref) {
        return ref != null && entries.containsKey(ref.cacheKey());
    }

    public Collection<ContainerRef> knownRefs() {
        List<ContainerRef> result = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) result.add(entry.ref);
        return result;
    }

    /**
     * 用当前打开着的容器槽位重建该容器的缓存。{@code containerStacks} 必须只包含
     * 容器自己的槽位（不含玩家背包），且来自服务端完整库存同步。
     */
    public void record(ContainerRef ref, List<ItemStack> containerStacks) {
        Entry entry = entries.computeIfAbsent(ref.cacheKey(), key -> new Entry(ref));
        entry.counts.clear();
        for (ItemStack stack : containerStacks) {
            if (stack == null || stack.isEmpty()) continue;
            Count existing = entry.find(stack);
            if (existing != null) {
                existing.count += stack.getCount();
            } else {
                entry.counts.add(new Count(stack, stack.getCount()));
            }
        }
        entry.counts.removeIf(count -> count.count <= 0);
    }

    public long knownCount(ItemStack stack) {
        long total = 0;
        for (Entry entry : entries.values()) {
            Count count = entry.find(stack);
            if (count != null) total += count.count;
        }
        return total;
    }

    public long knownCount(ContainerRef ref, ItemStack stack) {
        Entry entry = entries.get(ref.cacheKey());
        if (entry == null) return 0;
        Count count = entry.find(stack);
        return count == null ? 0 : count.count;
    }

    public boolean hasKnownSource(ItemStack stack) {
        return knownCount(stack) > 0;
    }

    /** 缓存里确实含有该物品的容器，按调用方给定的距离排序。 */
    public List<ContainerRef> sourcesOf(ItemStack stack) {
        List<ContainerRef> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            Count count = entry.find(stack);
            if (count != null && count.count > 0) result.add(entry.ref);
        }
        return result;
    }

    public boolean hasUnknown(Collection<ContainerRef> refs) {
        for (ContainerRef ref : refs) {
            if (!entries.containsKey(ref.cacheKey())) return true;
        }
        return false;
    }

    /** 取走之后同步扣减缓存，避免同一容器被反复当成还有料。 */
    public void subtract(ContainerRef ref, ItemStack stack, int amount) {
        Entry entry = entries.get(ref.cacheKey());
        if (entry == null || amount <= 0) return;
        Count count = entry.find(stack);
        if (count == null) return;
        count.count = Math.max(0, count.count - amount);
        entry.counts.removeIf(c -> c.count <= 0);
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new HashMap<>();
        for (Entry entry : entries.values()) {
            for (Count count : entry.counts) {
                result.merge(entry.ref.cacheKey() + "|" + count.sample.getItem().getTranslationKey(), (long) count.count, Long::sum);
            }
        }
        return result;
    }
}
