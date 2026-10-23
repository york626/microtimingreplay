# 微时序回放器（MicroTimingReplay）

一个 Fabric 模组：把服务端在一段时间内的**微时序事件**（方块更新、形状更新、计划刻、方块事件、活塞移动、实体移动/生成……）**录下来**，再**一步一步回放**——可以前进、后退、跳到任意一步，还能看到每个事件产生那一刻的 Java 调用栈。

---

## 目录

- [核心概念](#核心概念)
- [事件节点一览](#事件节点一览)
- [快捷录制（图形界面，推荐）](#快捷录制图形界面推荐)
- [快速上手（命令）](#快速上手命令)
- [指令总览](#指令总览)
- [配置档与区域管理](#配置档与区域管理)
- [紫色染料选区工具](#紫色染料选区工具)
- [录制](#录制)
- [回放](#回放)
- [时间轴界面与调用栈](#时间轴界面与调用栈)
- [游戏规则](#游戏规则)
- [数据存放位置](#数据存放位置)
- [注意事项](#注意事项)
- [许可证](#许可证)

---

## 核心概念

**配置档（Profile）**
一次录制的容器。每个配置档有自己的名字、若干**区域（Area）**、录到的所有游戏刻帧，以及配套的世界快照与调用栈文件。

**区域（Area）**
一个长方体范围 + 所属维度。**只有落在区域内的事件才会被记录**。一个配置档可以放多个区域，也可以跨维度。
如果一个区域都没有，则**不做任何范围过滤**（全世界的事件都记）——数据量会非常大，不建议这么用。

**帧 / 事件 / 步（Frame / Event / Step）**
录制以游戏刻（tick）为单位切成帧，每一帧内是一棵**事件树**：

```
阶段（Phase）→ 队列（Queue）→ 更新（Update）→ 叶子事件（添加计划刻 / 实体移动 / ……）
```

---

## 事件节点一览

时间轴上的每一行都是一个节点。这里按事件树的层级列出全部类型，以及它们对应 Minecraft 源码里的什么位置。

### 阶段（Phase）— 最外层

一个游戏刻里的大块执行阶段，按原版的真实执行顺序排列。颜色统一为**浅紫**。

| 时间轴显示 | 内部名 | 含义 | 对应原版 |
|---|---|---|---|
| 维度运算阶段 | `LevelTickPhase` | 一个维度的完整 tick，最外层容器 | `MinecraftServer.tickChildren` → `ServerLevel.tick()` |
| 区块运算 | `ChunkTickPhase` | 区块加载/卸载与区块随机事件的驱动 | `ServerChunkCache.tick(...)` |
| 冰与雪 | `IceAndSnowPhase` | 结冰、积雪、降水判定 | `ServerLevel.tickPrecipitation(...)` |
| 随机刻 | `RandomTickPhase` | 随机刻（作物生长、树叶衰减等） | `BlockState.randomTick(...)` |
| 计划刻阶段 | `ScheduledTickPhase` | 计划刻队列的执行 | `ServerLevel.tick` 里的 `LevelTicks.tick(...)` |
| 方块事件阶段 | `BlockEventPhase` | 方块事件队列的执行 | `ServerLevel.runBlockEvents()` |
| 实体运算阶段 | `EntityTickPhase` | 遍历所有实体做 tick | `EntityTickList.forEach(...)` |
| 方块实体阶段 | `BlockEntityPhase` | 遍历所有方块实体做 tick | `Level.tickBlockEntities()` |
| 龙战 | `DragonFightPhase` | 末影龙战斗逻辑（仅末地） | `EnderDragonFight.tick()` |
| 玩家运算阶段 | `PlayerTickPhase` | 处理玩家连接与收到的数据包；玩家操作引发的方块变更在这一段 | `MinecraftServer.tickConnection()` |
| 异步事件阶段 | `AsyncTaskPhase` | 服务端处理异步任务 | `MinecraftServer.waitUntilNextTick()` |

> 阶段本身没有坐标，因此**不会在世界里生成标记方块**。

### 队列层（Queue）— 单个待办项

阶段内部的一个具体待办项。`forward/backward queue` 就在这一层停。颜色**黄**（有子事件）或**红**（空的，说明什么都没触发）。

| 时间轴显示 | 内部名 | 含义 | 对应原版 |
|---|---|---|---|
| 执行方块计划刻 | `ExecuteBlockTick` | 执行某个方块的一次计划刻 | `ServerLevel.tickBlock(...)` |
| 执行流体计划刻 | `ExecuteFluidTick` | 执行某个流体的一次计划刻 | `ServerLevel.tickFluid(...)` |
| 执行方块事件 | `ExecuteBlockEvent` | 执行某个方块事件 | `ServerLevel.doBlockEvent(...)` |
| 实体运算 | `entityTick` | **单个**实体的 tick | `ServerLevel.tickNonPassenger(...)` |
| 方块实体运算 | `blockEntityTick` | **单个**方块实体的 tick | `TickingBlockEntity.tick()` |

### 更新层（Update）

方块更新的传播。颜色：邻居更新**红**，形状更新**青**。

| 时间轴显示 | 内部名 | 含义 | 对应原版 |
|---|---|---|---|
| 邻居更新 | `NeighbourUpdate` | 一次邻居更新（`neighborChanged`） | `NeighborUpdater.executeUpdate(...)` |
| 形状更新 | `ShapeUpdate` | 一次形状更新（`updateShape`），即状态更新 | `NeighborUpdater.executeShapeUpdate(...)`、`CollectingNeighborUpdater.shapeUpdate(...)` |

### 动作事件

| 时间轴显示 | 内部名 | 颜色 | 含义 | 对应原版 |
|---|---|---|---|---|
| 放置方块 / 放置方块(失败) | `setBlock` | 绿 / 红 | 一次 `setBlock`。**这是个作用域节点**，由它触发的方块更新是它的子事件。悬停可看方块状态的前后差异与 flag 位分解；失败（返回 `false`）时显示为红色，回放时也不会被重现 | `Level.setBlock(pos, state, flags, limit)` |
| 添加方块事件 | `addBlockEvent` | 黄 | 把一个方块事件**加入队列** | `Level.blockEvent(...)` |
| 添加计划刻 | `addScheduleTick` | 黄 | 把一个计划刻**加入队列**。悬停可看触发刻、优先级、子顺序 | `LevelTicks.schedule(...)` |
| 发布游戏事件 | `postGameEvent` | 深青 | 发出一个游戏事件（幽匿感测系统的信号） | `ServerLevel.gameEvent(...)`、`GameEventDispatcher` |
| 收到游戏事件 | `receivedGameEvent` | 深青 | 幽匿感测体收到振动。会同时标出**振动源**位置 | `VibrationSystem.Ticker.receiveVibration` |
| 活塞生成 / 活塞移除 | `movingPiston` | 浅紫 | 移动中的活塞方块实体出现或消失 | `Level.setBlockEntity(...)`、`PistonMovingBlockEntity.finalTick` |
| 活塞移动 | `movingPistonTick` | 浅紫 | 移动中的活塞每一刻的推进进度，回放时用方块展示实体模拟 | `PistonMovingBlockEntity.tick(...)` |
| 实体生成 / 实体移除 | `entitySpawn` | 绿 / 深红 | 实体进入或离开录制区域（也包括真正的生成与死亡） | `ServerLevel.addEntity`、`Entity.onRemoval`，以及跨区域边界移动 |
| 实体移动 | `entityMove` | 金 | 一次实体位移，记录前后坐标与速度 | `Entity.move(MoverType, Vec3)` |
| 无形步骤 | `invisibleStep` | 白 | `BlockPosEvent` 基类的兜底类型，正常不该出现；一旦出现，通常说明有别的东西在往事件树里乱塞节点 | — |

### 关于颜色

| 颜色 | 含义 |
|---|---|
| 浅紫 | 阶段 / 活塞 |
| 黄 | 队列项、入队动作 |
| 红 | 邻居更新；或**空的**队列 / 方块实体运算；或**失败的** setBlock |
| 青 | 形状更新 |
| 绿 | 成功的 setBlock、实体生成 |
| 金 | 实体运算 / 实体移动 |
| 深青 | 游戏事件（发布 / 接收） |
| 灰 | 正在**退出**某个作用域（EXIT 节点） |

节点末尾的 `∅`（空集合符号）表示这是个**空节点**，它没有产生任何子事件。

---

## 快捷录制（图形界面，推荐）

不想记命令就走这条路：**用 Litematica 框选 → 点两下鼠标** 就能录一段。

1. 用 **Litematica** 框好要观察的区域（插件式软依赖，没装就没有这个入口）
2. **按住 `Left Alt`** 打开面板 → 点「**动态记录**」
3. 界面上会显示选区名、两个角点、体积（上限 **1,000,000 格**）→ 点「**● 开始动态记录**」
   · 服务端会自动建同名配置档（同名**覆盖**）并把选区加成区域，维度取你当前所在维度
4. 录制中左上角有角标：`● 录制中 12/5000 刻 · 34 事件`（单次上限 **5000 刻**，到点自动停）
5. 停止：**按住 `Left Alt`**，面板第一张卡会变成「**■ 停止记录 N 刻**」，点它即可保存
   · 停止后会提示「已保存：<名字>（N 刻 / M 个事件）」，数据落到 `config/mtr_profiles/…`

> 录制前建议先 `/tick freeze`（模组不会自己改时间流速）。
> 名字怎么来：默认取 Litematica 选区的名字，非法字符会被替换成 `_`。

### 回放（也是一键）

还是那个面板（按住 `Left Alt`）→ 点「**▶ 回放 <名字>**」，或者进「动态记录」界面点「**▶ 回放这段**」。
回放会自动打开**时间轴界面**，用下面的按钮前进/后退；再点一次「**■ 停止回放**」还原现场。

时间轴上：

- 每个 `── Tick N ──` 分组里，**真正改世界的事件前面有 `1.` `2.` `3.`** —— 这就是「这个游戏刻里谁先动手」。
  阶段 / 队列 / 更新这些只是缩进容器，不编号。
- **点某一行 → 世界里那个方块会亮一下**（临时高亮，下一次步进自动清掉），不用真的跳过去。
- 行尾 `[#N↗]` 才是跳步，`[$]` 看调用栈。

> 回放会真改世界（`replay start` 把区域还原到录制起点、`stop` 再还回来）。
> 中途崩服/强杀的话，重新 start 一次再正常 stop 就能恢复现场。

---

## 快速上手（命令）

```
/tick freeze
```
```
/mtr profile create test
```
```
/mtr profile area add test 100 60 100 120 80 120
```
```
/mtr record test 20
```

录制会在 20 游戏刻后自动停止（也可以 `/mtr record stop` 手动停）。

```
/mtr replay start test
```
```
/mtr replay forward steps 1
```
```
/mtr replay screen
```
```
/mtr replay stop
```

---

## 指令总览

所有指令的根节点是 `/mtr`，需要**管理员权限**。

```
/mtr
├─ profile
│  ├─ create <name>
│  ├─ delete <name>
│  ├─ info   <name>
│  ├─ migrate <name>
│  └─ area
│     ├─ add    <name> <pos1> <pos2> [area_name]
│     ├─ remove <name> <area_name>
│     └─ modify
│        ├─ pos    <name> <area_name> <pos1> <pos2>
│        └─ rename <name> <area_name> <new_name>
├─ record
│  ├─ <name> <ticks>
│  ├─ stop
│  └─ clear <name>
└─ replay
   ├─ start <name>
   ├─ stop
   ├─ subscribe
   ├─ unsubscribe
   ├─ screen   [page]
   ├─ forward  <unit> [amount]
   ├─ backward <unit> [amount]
   ├─ jump     <step>
   ├─ dump     <step>
   └─ auto
      ├─ stop
      └─ <direction> <unit> <delay> <steps>
```

---

## 配置档与区域管理

### `/mtr profile create <name>`

新建一个配置档。名字只能使用 `A-Z a-z 0-9 _ . + -`。
同名已存在则失败。创建后会顺手执行一次 `info`（即进入预览状态，把区域显示出来）。

### `/mtr profile delete <name>`

删除配置档。**不可撤销。**

### `/mtr profile migrate <name>`

把存放在全局目录（`config/mtr_profiles/`，也就是不在存档文件夹里的那份）的配置档**复制**到当前存档。

### `/mtr profile info <name>`

**选区预览的开关指令**

- 第一次执行：把该配置档设为「当前正在查看的配置档」，在当前维度用玻璃方块展示实体把所有区域标出来，并打印统计信息（创建时间、录制刻数、帧数、区域数）。**同时启用[紫色染料选区工具](#紫色染料选区工具)。**
- 再次执行则退出查看状态，选区工具随之失效。

> 这个「正在查看」状态是**全服唯一**的。

### `/mtr profile area add <name> <pos1> <pos2> [area_name]`

给配置档添加一个长方体区域，维度取自**执行指令的人所在的维度**。
`pos1` / `pos2` 支持相对坐标（`~ ~ ~`）和局部坐标，但**必须是已加载的方块**。
`area_name` 省略时自动分配递增编号（`1`、`2`、`3`…）。名字重复则失败。

### `/mtr profile area remove <name> <area_name>`

删除指定区域。`area_name` 支持 Tab 补全。

### `/mtr profile area modify pos <name> <area_name> <pos1> <pos2>`

修改已有区域的两个角点坐标（维度不变）。

### `/mtr profile area modify rename <name> <area_name> <new_name>`

给区域改名。新名字已被占用则失败。

---

## 紫色染料选区工具

当 `/mtr profile info <name>` 处于**激活状态**时，手持**紫色染料**：

| 操作 | 效果 |
|---|---|
| 左键点方块 | 设置 **角点 1** |
| 右键点方块 | 设置 **角点 2** |
| **潜行 + 右键** | 确认并保存当前选区为一个新区域（自动编号） |
| **潜行 + 左键** | 清空当前选区 |

只设了一个角点时，另一个角点会**实时跟着准星**（4 格以内取命中的方块，否则取视线终点），并用半透明方块展示实体动态预览选区。

---

## 录制

### `/mtr record <name> <ticks>`

开始录制。

> **重要**：如果此前已经录过一段，再执行 `record` 会**接着往后追加**，不会从头覆盖。想从头来请先 `/mtr record clear <name>`。

> **重要**：先 `/tick freeze` 能更好地控制录制。

### `/mtr record stop`

手动停止录制。

### `/mtr record clear <name>`

清空该配置档的所有帧数据，区域配置保留。
该配置档正在录制或回放时拒绝执行。

---

## 回放

### `/mtr replay start <name>`

先把你**当前**区域内的世界状态备份成 `<name>_replay.dat`（用于结束时还原现场），
再用 `<name>_record.dat` 把区域还原到**录制开始时**的状态。

此时光标停在第 0 步，用下面的指令往前走。

### `/mtr replay stop`

还原 `_replay` 备份（把你的现场还回来），清掉所有标记实体与回放实体，关闭所有订阅者的 BossBar 与侧边栏。

### `/mtr replay forward <unit> [amount]`
### `/mtr replay backward <unit> [amount]`

按指定**单位**前进 / 后退。`amount` 省略时为 `1`。

| unit | 含义 |
|---|---|
| `ticks` | 按**游戏刻**推进 `amount` 刻，执行这期间的所有事件 |
| `steps` | 推进 `amount` 个**可见步**（受 `step_ignore_updates` / `step_ignore_exiting` 影响） |
| `phase` | 推进到**下一个阶段**（计划刻阶段 / 方块实体阶段……）的边界 |
| `queue` | 推进到**下一个队列**的边界（方块事件 / 计划刻 / 流体刻 / 实体运算 / 方块实体运算……） |
| `updates` | 推进到**下一个更新**（邻居更新 / 形状更新）的边界 |

后退时会**逆向应用**每个事件（setBlock 还原成旧状态、实体生成还原成移除，等等）。

### `/mtr replay jump <step>`

直接跳到指定的 **step 编号**（时间轴界面里 `[#N↗]` 显示的那个数字）。

### `/mtr replay auto <direction> <unit> <delay> <steps>`

自动步进：每隔 `<delay>` 个游戏刻，朝 `<direction>`（`forward` / `backward`）方向按 `<unit>` 走一步，共走 `<steps>` 步。

> 配合 Flashback 这类录像模组很好用。

### `/mtr replay auto stop`

停止自动步进。

### `/mtr replay subscribe` / `unsubscribe`

订阅 / 取消订阅回放的 **BossBar** 与**侧边栏时间轴**。

---

## 时间轴界面与调用栈

### `/mtr replay screen [page]`

打开**对话框式的时间轴**，每页 40 条。每行包含：

- `[TickN]` — 该事件所属的游戏刻，换刻处有分隔线
- 缩进 + `▶`（进入一个父级作用域）或 `→`（叶子事件）
- 事件描述，**悬停可看该事件的完整信息**（坐标、维度、方块状态前后差异、setBlock 的 flag 位分解等）
- 当前光标所在的那一行会**加粗 + 下划线**
- 行尾 `[$]` — 点击查看该步的**调用栈**
- 行尾 `[#N↗]` — 点击**跳到该步**

底部有 `◀ 上一页` / `下一页 ▶` / `✕ 关闭` 按钮。

### `/mtr replay dump <step>`

打开指定 step 的 **Java 调用栈**对话框。栈内容按包名 / 类名 / 方法名 / 文件行号分色显示，放在一个多行文本框里方便**全选复制**；标题行支持点击复制到剪贴板、悬停预览前 20 行。

调用栈是**录制时**在每个事件构造那一刻抓的，随配置档一起存到 `mtr_stacktrace/<name>.dat`。模组自身的栈帧和 mixin 合成方法会被过滤掉。

---

## 游戏规则

全部位于 `/gamerule` 的 **MISC** 分类下，都是布尔值。

| 游戏规则 | 默认值 | 作用 |
|---|---|---|
| `microtimingreplay:skip_empty_phase` | `true` | 录制时丢弃**没有产生任何子事件**的阶段节点 |
| `microtimingreplay:skip_empty_queue` | `true` | 录制时丢弃**空的队列节点** |
| `microtimingreplay:skip_empty_update` | `true` | 录制时丢弃**空的更新节点**（这类节点极多，建议保持开启） |
| `microtimingreplay:skip_empty_entity_tick` | `false` | 录制时丢弃**没有产生任何子事件的实体运算**（场景里有实体时建议关闭） |
| `microtimingreplay:skip_empty_block_entity_tick` | `true` | 录制时丢弃**没有产生任何子事件的方块实体运算** |
| `microtimingreplay:step_ignore_updates` | `true` | `forward/backward steps` 时**跳过**方块更新，不把它算作一步 |
| `microtimingreplay:step_ignore_exiting` | `true` | `forward/backward steps` 时**跳过**「退出作用域」事件 |
| `microtimingreplay:skip_delta_changes` | `true` | 回放渲染时**只标当前这一步**；关掉后会把本次跨越的所有位置一起标出来（大跨度跳转时便于看全貌，但也容易乱） |

前五条（`skip_empty_*`）影响**录制产物的体积**，改动只对之后的录制生效。后三条只影响**回放时的手感与显示**，随时可改。

---

## 数据存放位置

都在 **Fabric 配置目录**里（单人是 `.minecraft/config/`，服务端是 `config/`）：

| 路径 | 内容 |
|---|---|
| `config/mtr_profiles/<world-key>/<name>.dat` | 当前存档的配置档本体：区域定义 + 全部事件帧（压缩 NBT） |
| `config/mtr_backups/<world-key>/<name>_record.dat` | 当前存档中「录制起点」的区域世界快照 |
| `config/mtr_backups/<world-key>/<name>_replay.dat` | 当前存档中「回放开始前」的现场快照 |
| `config/mtr_stacktrace/<world-key>/<name>.dat` | 当前存档中每一步对应的调用栈 |

> 数据按存档自动隔离。`<world-key>` 由存档路径生成，名字里带可读的存档目录名和一段短哈希；所以不同存档可以放心用同名配置档。

---

## 注意事项

- **录制前先 `/tick freeze`。** 模组不会自己冻结或解冻时间。回放不受影响，没冻结也能正确步进。
- **回放会真改世界。** `replay start` 会把区域还原到录制起点，`replay stop` 再还原回来。如果回放过程中**服务器崩了或被强杀**，`_replay` 备份不会被自动还原，你的现场会停在录制起点的状态——这时重新 `/mtr replay start` 再正常 `stop` 一次即可恢复。
- **区域内的非玩家实体会被备份与还原。** 还原时先清空区域内实体，再从备份重建。
- **没有区域 = 不过滤。** 没有区域的配置档会记录全世界的事件，请谨慎使用。
- `subscribe`、`unsubscribe`、`screen`、`dump` **必须由玩家执行**（它们要么给玩家开界面，要么绑定在玩家身上）。其余指令控制台和命令方块都能跑。
- `/mtr profile info` 的「当前查看中」状态是全服共享的单一变量，多人同时操作会互相干扰。

---

## 许可证

MIT 许可证，见 [LICENSE](LICENSE)。
