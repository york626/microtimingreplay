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

### ★ 冻结回放 + 活塞：`moving_piston`（B36）那条链（2026-10-23 定型）

| 环节 | 事实 |
|---|---|
| B36 自身 | **`moving_piston` 渲染成空气**；"正在移动的方块"原版靠 `PistonMovingBlockEntity` 的渲染器画，而回放只把方块状态写回世界、**不建那份方块实体** ⇒ 冻结回放时全靠 `PistonDisplayManager` 的 `BlockDisplay` 替身 |
| 替身的地位 | **不是装饰，是唯一渲染来源** ⇒ ① 不受 `showBlockMarkers` 控制 ② 不受「标记：隐藏」控制（`isHideableMarker()` 而非 `isMarker()`）③ 不做"关掉所有标记"这种一刀切 |
| 替身在服务端 | 每步重建：`renderCurrentState` 回放活塞事件（不限 `LEAF`，只排除 `EXIT`）＋ 兜底 `renderB36StandIns()`（按**世界真实方块状态** `is(Blocks.MOVING_PISTON)` 补）＋ 复用旧替身前先 `anyLive()` 查存活 |
| 诊断 | 日志 `[mtr-piston] 步 X ｜ 世界里 B36 N 处 ｜ 本轮新造替身 M 处 ｜ 已登记 K 处` |
| 排查"看不见" | **先看 `config/mtr/client.json` 的 `hideMarkers`**（客户端开关，v1.3.18 的真凶），别只盯服务端 |

> ⚠️ **截至 v1.3.18 仍未最终修好**（Ray 2026-10-23 21:40 回退 `v1.3.6`）。**再接手先做**：把 `hideMarkers` 设回 false 复测，并比对 `[mtr-piston]` 的 K 与画面。
> 历史：v1.3.7 去掉 `stepGameIfPaused(2)`（回放不再推进真实游戏刻）⇒ B36 不再被真实 tick 推完 ⇒ 这个坑才暴露。
