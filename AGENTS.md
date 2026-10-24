# microtimingreplay（微时序回放器）—— 我们维护的 fork

> 上游 `hotpad100c/MicroTimingReplay` 已停更 ⇒ **由我们自己修 bug / 加功能**。
> 远端：`origin` = `york626/microtimingreplay`、`upstream` = 上游（只用来对差异）。
> ★ **只维护 `1.21.1` 分支**：`master`（26.x）已删，别去复活它。

## 构建

项目用 **Loom 1.17 + 官方 Mojang 映射**，JDK 21。中文路径没问题，但本机有两道网络坎（详见主项目
`docs/architecture.md`「构建命令」）：

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.8.9-hotspot"
cd "/c/Users/Ray/Documents/MC修改/兄弟工程/microtimingreplay"
env -u https_proxy -u HTTPS_PROXY -u http_proxy -u HTTP_PROXY \
  ./gradlew build --console=plain --no-daemon \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<$https_proxy 里的端口>
```

- Gradle 发行版从 GitHub 下（会 PKIX 失败）⇒ 用 `gh-proxy.com` 下 zip 塞进 `~/.gradle/wrapper/dists/gradle-<ver>-bin/<hash>/`
- `maven.fabricmc.net` 直连只有 ~8 KB/s ⇒ 必须给 Gradle 传代理参数；**别把端口写进 `gradle.properties`**（每个会话会变）
- 产物名＝`microtimingreplay-<模组版本>-<MC版本>.jar`（`remapJar` 里改的）

## 代码地图

| 位置 | 作用 |
|---|---|
| `record/QuickRecordService` | 「动态记录」服务端：建配置档＋加区域＋开录/停录、5000 刻上限、1,000,000 格上限 |
| `network/MTRPayloads` + `MTRNetworking` | 所有包与 handler；**客户端永远只能提请求，服务端说了算**（op 权限 2） |
| `client/screen/QuickRecordScreen` | 图形界面（选区信息 + 开始/停止 + 更多玩法） |
| `client/QuickRecordHud` / `QuickRecordState` | 录制角标与客户端状态 |
| `client/LitematicaSelection` | **反射**读 Litematica 选区（公开仓库不塞第三方 jar，CI 才能编过） |
| `profile/` `record/` `replay/` | 原有引擎：配置档、帧/事件树、快照回放、调用栈 |

## 硬约定

- **不改数据格式**：新功能只加在入口/展示层，老档案必须还能放（`MTRProfile` / `ProfileManager` 那套不动）
- **第三方模组一律走反射**（Litematica / MaLiLib）：不往 `libs/` 提交 jar（许可证 + GitHub Actions 编译失败）
- **GUI 类改动 AI 无法自测** ⇒ 交付时写清「点哪里、判据是什么」，由 Ray 进游戏验
- **发版**：先 `git commit && git push` 再打 tag `v<版本>`，然后
  `python versions/tools/release_mod.py --publish microtimingreplay --jar <jar> --note "这次改了什么"`
  （正文只写本次改动；tag/附件名形如 `microtimingreplay-1.1.0-1.21.1.jar`）
- **⚠️ 不要在 GitHub 页面点「同步分叉 / Sync fork」**：我们通常 `ahead_by>0 / behind_by=0`，
  点它等于「Discard commit」，会把我们的修复丢掉

### ★ 冻结回放 + 活塞：`moving_piston`（B36）那条链（v1.3.19 定型：**提前落位**）

| 环节 | 事实 |
|---|---|
| 根因 | B36 自身渲染成空气；原版靠 `PistonMovingBlockEntity.tick` 在**世界真的走刻**时把它替换成落点方块（`setBlock(pos, movedState, 67)`，已反编译实锤）。v1.3.7 起回放不推进真实游戏刻 ⇒ 这个 tick 永远等不到 ⇒ B36 挂成空洞 |
| 修复（v1.3.19） | **`ReplaySession.settleMovingPistons()`**：每步把世界里还挂着的 B36 直接写成录制里记下的落点状态（`SILENT_SET_BLOCK`：同步客户端、不触发邻居更新）。数据源＝录制本身的完结事件（`SetBlockEvent` 的 `from=moving_piston`），光标之后最近一条优先，出生事件兜底 |
| 已退役 | **替身展示体（BlockDisplay）那套整体下线**（1.3.10~1.3.18 反复修不好的渲染链）：`MovingPistonEvent/MovingPistonTickEvent.display()` 回到通用玻璃块高亮；`PistonDisplayManager` 只留清理职责（clearAll/开机清残留/设置页一键清理） |
| 诊断 | 日志 `[mtr-piston] 步 X | 提前落位 B36 N 处`（只在真落位时打） |
| 已知边界 | 回放期间**不展示「移动中」的中间态**，一律显示落点结果（方块比真实时间早 1~2 刻落位）；时间轴里 `Piston Spawn/Tick/Remove` 事件照旧逐条可查 |

> 历史教训（别再走回头路）：
> ① v1.3.10 曾把「不画玻璃标记」一刀切套到活塞替身上 ⇒ 替身是渲染来源不是装饰，不能跟普通标记一起关；
> ② v1.3.9 把 `mtr_piston_display` 算进 `isMarker()` ⇒ `hideMarkers:true` 时 `MarkerVisibilityMixin` 把替身整个藏掉——**同一个判定函数被两处用在相反目的时拆成两个谓词**；
> ③ 排查客户端"看不见"先看 `config/mtr/client.json`（如 `hideMarkers`），别只盯服务端；
> ④ 替身系统的死穴＝它永远在和世界真实状态对账（过期替身叠在真方块上、替身死了占位不画图）——**与其造渲染替身，不如让世界本身没有不可渲染的方块**。
