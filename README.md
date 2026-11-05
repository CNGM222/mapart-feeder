# Mapart Feeder · 地图画自动供料

[![build](https://github.com/CNGM222/mapart-feeder/actions/workflows/build.yml/badge.svg?branch=1.21.11%2BFabric)](https://github.com/CNGM222/mapart-feeder/actions/workflows/build.yml)

Minecraft Java **1.21.11 / Fabric** 纯客户端 mod。配合 **Litematica** 与 **投影打印机**（litematica-printer）使用：

- 你只负责登记原料容器、指定地图画中心；
- 它按当前投影的材料清单缺口，走到容器边**快速拿取**原料，装满背包；
- 原料就绪后走到地图画区域，**蛇形扫过 128×128 单层**，让投影打印机逐块放置；
- 背包里对打印有用的原料用完，就回到地图画中心，**请求刷新材料清单**，再取料、再打印；
- 清单没有缺口时停在中心并关闭自动供料。

放置方块的逻辑完全交给投影打印机；本 mod 只做“取料、走路、开关打印、刷新清单”。

## 前置

- Fabric Loader + Fabric API（1.21.11）
- [Litematica](https://github.com/sakura-ryoko/litematica) 与 MaLiLib
- 投影打印机（BiliXWhite / aleksilassila 系 `litematica-printer`）
- 客户端需要真实的行走空间：地图画区域下方最好有可站立的地面或平台；本 mod 不飞行、不搭桥、不挖方块

## 按键（默认，可在“选项 → 控制 → 按键绑定 → 地图画供料”修改）

| 按键 | 作用 |
| --- | --- |
| **K** | 准星对准箱子/木桶/潜影盒：登记为原料容器；再按一次取消登记 |
| **J** | 指定地图画中心：准星指向方块就取该方块，否则取脚下方块 |
| **F8** | 开关自动供料 |
| **F9** | 清空本次会话的容器内容缓存，重新勘察 |

## 使用流程

1. 先把所有装原料的箱子/木桶/潜影盒逐个用 **K** 登记（双箱只登记一次即可）。
2. 站在地图画中心附近，用 **J** 指定中心（中心是 128×128 区域的几何中心，允许取 4 个中心方块之一）。
3. 选中并启用要打印的 Litematica 投影，按投影打印机原来的方式配置好（打印模块、放置间隔等）。
4. 按 **F8** 开始。首次运行会依次打开登记过的容器读取内容并缓存，之后找料不再重复开箱。
5. 打印阶段本 mod 会自动打开投影打印机的“打印模块 + 总开关”；按 **F8** 停止时会恢复你原来的开关状态。

## 行为细节

### 缺口怎么算

缺口来自当前**选中且启用**的 Litematica 投影的材料清单：

```
需求 = multiplier == 1 ? countMissing : countTotal × multiplier
缺口 = max(0, 需求 - countAvailable)
```

`countAvailable` 是背包里该物品的数量，所以“已经拿在身上的原料”不会被重复搬运。

### 取料规则

- 只从你用 **K** 登记过的容器取料；不会顺手拿旁边没登记的箱子。
- 物品按“物品 + 组件”精确匹配，不会把同名不同 NBT 的东西混在一起。
- 优先使用原版 `QUICK_MOVE`（Shift 快速移动）；缺口不足一组时用光标精确拆分，余料归还源槽位。
- 背包优先合并到未满的同种堆，再占用空格；不使用护甲格、合成格、副手。
- 打开容器后先等服务端**完整库存同步**再操作；取完再请求一次完整同步核对，不把客户端预测当成成功。
- 容器内容会缓存到本次游戏会话的内存里；你手动改过箱子内容时按 **F9** 清缓存即可。

### 哪些原料会被跳过

如果某样缺口原料在**所有已登记容器**里都没有（容器都勘察过、缓存里数量为 0），它会被跳过并在聊天栏提示一次；其余原料照常取、照常打印。跳过列表在重新开始（F8 关闭再开）或切换投影后清空。

### 打印阶段

- 打印区域是“以中心为几何中心、边长 `2 × printAreaHalfSize`、高度 1 层”的正方形，默认 128×128。
- 本 mod 按配置的车道间距（默认 6 格）蛇形扫过整层，让打印机在交互距离内逐块放置。
- 扫完一层（或背包里已经没有任何清单需要的原料）后，回到中心请求 Litematica 重新统计材料。
- 清单没有缺口 → 完成并停在中心。
- 连续两次刷新后剩余方块数没有减少，且已无法再取到原料 → 停止并提示“剩余方块可能无法放置”（常见原因：被遮挡、缺支撑、区域不在交互距离、打印机没有真正开启）。

## 配置

首次启动生成 `config/mapartfeeder.json`，退出游戏后编辑，重启生效。

| 字段 | 默认 | 说明 |
| --- | ---: | --- |
| `unobtrusiveInteraction` | `true` | 无感开箱：只在本机隐藏自动打开的容器界面，服务端仍是正常开箱 |
| `autoTogglePrinter` | `true` | 打印阶段自动打开打印机，停止时恢复原开关 |
| `printLaneSpacing` | `6` | 蛇形扫层的车道间距（格）；交互距离约 4.5 格，6 格能覆盖整层 |
| `printAreaHalfSize` | `64` | 打印区域半边长；128×128 地图画用 64 |
| `materialCheckIntervalTicks` | `20` | 打印中检查“原料是否用完”的间隔 |
| `openTimeoutTicks` | `40` | 开箱与完整库存等待上限 |
| `syncTimeoutTicks` | `80` | 取料后请求完整同步的等待上限 |
| `clickIntervalTicks` | `2` | 单 tick 点击预算用尽时，下一批的间隔 |
| `emptyContainerCooldownTicks` | `200` | 没取到东西的容器再次尝试前的最短间隔 |
| `visitedContainerCooldownTicks` | `100` | 取完东西的容器再次尝试前的最短间隔 |
| `recountTimeoutTicks` | `300` | 在中心请求重新统计材料后的等待上限 |
| `containers` | `[]` | 登记过的容器（维度 + 坐标），由 **K** 自动维护 |
| `center` | `null` | 地图画中心（维度 + 坐标），由 **J** 自动维护 |

20 tick ≈ 1 秒。

## 已知边界

- 容器内容只有真正打开才能知道，所以第一次运行会逐个勘察登记的容器；登记很多容器时请留出时间。
- 未登记容器里的原料不会被发现；原料换箱子后记得重新 **K** 登记。
- 本 mod 不挖方块、不搭桥、不飞行；地图画区域不可步行到达时会在聊天栏提示并跳过走不到的点。
- 打印机自身是否真正放置，取决于它的配置（打印模块、放置间隔、范围、投影选择）。本 mod 只负责把角色带到正确位置并在需要时打开它。
- 服务端若禁止自动操作，客户端 mod 无法绕过；请只在允许的世界/服务器使用。

## 构建

Windows：

```bat
build.cmd build
```

首次需要会话目录里的 `.gradle-dist`（Gradle 9.2.1）与 JDK 21。构建缓存与临时目录都放在会话目录，不污染系统目录。产物：`build/libs/mapart-feeder-0.1.0.jar`（不要放 `-sources.jar`）。

## 实现说明

- 容器内容、投影材料都通过反射读取 Litematica 的公开 getter，不写它们的文件；
- 打印机的开关与刷新通过反射访问：原版 `Configs.PRINT_MODE`，或 BiliXWhite 分支的 `Configs.Core.WORK_SWITCH` / `Configs.Print.ENABLED` 与 `MissingMaterialTracker`；找不到接口时会在聊天栏提示；
- 所有交互都走原版：真实走路、真实右键开箱、真实 `clickSlot`，不发送自造数据包、不直接改世界。

## 构建（开发者）

```bat
build.cmd build
```

仓库同时提交了 Gradle Wrapper，GitHub Actions 会用它构建；本地没有网络或缺少服务端 jar 时，可用 `clientOnlyMinecraftJar()` 离线编译（见 `build.gradle`）。

