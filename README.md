# 小世界领域 / Small World Domain

> Minecraft **1.21.1** · **NeoForge** · Java 21

一个「拥有自己的微型维度」的实体模组。领域之主（Domain Lord）平时在主世界游荡，
当玩家靠近到 14 格以内，它会展开领域：先收缩一圈粒子环蓄力 2.5 秒，然后把半径 16 格内的所有玩家
**连同它自己**一起拖进一个只属于它的漂浮小世界，在里面继续打；40 秒后（或它被打死、玩家全离开）
所有人被送回原坐标。

![](https://img.shields.io/badge/MC-1.21.1-3C8527) ![](https://img.shields.io/badge/NeoForge-21.1.x-EA8C1E)

> **状态**：源码完整（10 个类 + 维度数据包 + 贴图 + 语言文件），已通过语法校验与 Gradle 脚本校验；
> **本地环境无法完成首次编译**（官方源被墙 + 该构件无国内镜像，详见「依赖仓库说明」）。
> 已配好 GitHub Actions 流水线，推到 GitHub 即可自动产出 jar。

---

## 玩法链路

| 阶段 | 表现 |
| --- | --- |
| 待机 | 出 8 秒后进入「监视」状态，14 格内有非创造玩家就触发 |
| 蓄力 2.5s | 发光、粒子环从 16 格向内收缩、信标音、自带 Boss 血条 |
| 展开 | 半径内玩家被拉进 `smallworld:domain` 维度；每个玩家落在圆形竞技场上不同方位 |
| 领域内 | 黑石圆台 + 隐形力场墙 + 灵魂灯笼边界，中央祭坛；Boss 一起进来，血条持续显示；动作栏倒计时 |
| 收束 | 40 秒到 / Boss 被击杀（小世界崩塌）/ 玩家全部离开 → 所有人回到进入前的坐标与朝向 |

## 内容清单

- 实体 `smallworld:domain_lord`（领域之主，80 血 / 6 攻 / 6 甲 / 击退抗性 0.85）
- 物品 `smallworld:domain_core`（领域核，右键开一个只属于你的 30 秒小世界，再右键提前退出）
- 物品 `smallworld:domain_lord_spawn_egg`（刷怪蛋）
- 维度 `smallworld:domain`（数据包定义：`data/smallworld/dimension/domain.json`）
- 创造模式标签页「小世界」

## 命令（OP 2 级）

```
/smallworld summon    在你面前召唤一个领域之主
/smallworld enter     直接把自己送进小世界（60 秒，用来快速验证维度）
/smallworld exit      立即离开小世界
/smallworld rebuild   重建竞技场方块
/smallworld status    查看当前领域人数与剩余秒数
```

## 源码结构

```
src/main/java/com/smallworld/
├── SmallWorld.java              模组入口
├── SWRegistries.java            实体 / 物品 / 创造页注册
├── SWEvents.java                NeoForge 事件（属性、tick、命令、登出、复活兜底）
├── entity/DomainLord.java       领域之主：AI、蓄力、展开领域
├── domain/DomainManager.java    领域会话：拉人、计时、送回、边界情况
├── domain/DomainArena.java      在空维度里就地生成竞技场
├── item/DomainCoreItem.java     玩家自用领域核
├── command/SWCommand.java       /smallworld 命令
└── client/SWClient.java         实体渲染器注册
```

## 构建

### 方式一：GitHub Actions（推荐，不需要本地能连官方源）

仓库已带 `.github/workflows/build.yml`，推上去就自动编译。

1. 在 GitHub 新建一个空仓库（Public/Private 都行）；
2. **把这个 `smallworld` 目录作为仓库根目录**推上去：

```bash
cd smallworld
git init
git add .
git commit -m "small world domain mod"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

3. 打开仓库的 **Actions** 标签页 → 跑完的 `build` 任务 → 页面底部 **Artifacts** 下载 `smallworld-jar`；
4. 打 tag 会额外触发 `release` 任务，自动把 jar 挂到 Release：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

流水线做的事：JDK 21（temurin）→ Gradle 缓存 → `./gradlew build` → 上传 `build/libs/*.jar`。
runner 在海外，能直连 `maven.neoforged.net`，所以不涉及任何镜像/代理问题。

> 如果你的仓库根目录是上一层（`smallworld` 只是子目录），把 `.github/` 挪到仓库根，
> 并在 workflow 的每个 `run` 步骤加上 `working-directory: smallworld`。

### 方式二：本地构建

需要 **JDK 21** 且能访问 `maven.neoforged.net`。

```bash
# Windows
gradlew.bat build
# macOS / Linux
./gradlew build
```

产物：`build/libs/smallworld-1.0.0.jar`，丢进 `.minecraft/mods` 即可（客户端与服务端都要装）。
首次构建会下载 NeoForge 21.1.x 并反编译 Minecraft（约 5–15 分钟）。

国内网络想加速，可加参数让 NeoForge 构件走 BMCLAPI 镜像：

```bash
./gradlew build -PcnMirror=true
```

（镜像只能解决 `neoforge` / `neoform` / Minecraft 库文件，`neoform-runtime` 仍然必须走官方源。）

### 依赖仓库说明（重要）

ModDevGradle 运行时**必须**从官方仓库拉 `net.neoforged:neoform-runtime`，这个构件国内没有镜像
（BMCLAPI 只镜像了 `neoforge` 本体和 `neoform`）。而且 Gradle 遇到 TCP 超时不会自动回落到下一个仓库，
所以只要连不上 `maven.neoforged.net`，构建就一定会失败——这正是本机当时卡住的原因。

三条出路：走上面的 CI、本机开代理、或在有网络的机器上 build 一次后把 `~/.gradle/caches` 整包拷过来
（之后可以 `./gradlew build --offline` 离线构建）。

## 已知限制 / 可扩展方向

1. **同一时刻只允许一个领域**（共享一张小世界地图）。要并发多个：把 `DomainManager.active`
   换成 `Map<UUID, Session>`，并给每个会话分配不同的 `ARENA_CENTER`（错开坐标即可，竞技场是按坐标即时生成的）。
2. 竞技场是每次展开时幂等重建的，不保存状态；想做成「领域会成长/被破坏后保留痕迹」，需要引入 `SavedData` 持久化。
3. 目前只做了单个实体持有领域。想做成「每个生物都有领域」，把 `DomainManager.openDomain` 的调用方抽象成接口即可。
