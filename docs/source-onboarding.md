# GKD 源码导览与贡献指南

这份文档面向第一次阅读或准备贡献 GKD 源码的人。它不替代用户文档，而是帮你快速建立源码地图：入口在哪里、规则如何流动、改某类功能应该先看哪些文件，以及提交前怎样验证。

## 项目概览

GKD 是一个 Android 应用，通过无障碍服务或自动化能力读取当前界面节点，使用类似 CSS 的高级选择器匹配目标节点，再按订阅规则执行点击、返回、截图等动作。

仓库是 Gradle 多模块结构：

| 模块 | 作用 |
| --- | --- |
| `app` | Android 应用主体，包含 Compose UI、无障碍/自动化服务、Room 数据库、订阅管理、日志、截图、HTTP 调试服务等 |
| `selector` | Kotlin Multiplatform 选择器解析与匹配库，可用于 JVM 和 JS |
| `hidden_api` | Android 隐藏 API 的编译期声明，供 `app` 以 `compileOnly` 方式引用 |

## 技术栈

| 层 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.3.x，Java 11 target |
| Android | Android Gradle Plugin 9.x，compileSdk/targetSdk 37，minSdk 26 |
| UI | Jetpack Compose，Material 3，Navigation 3 |
| 异步与状态 | Kotlin Coroutines、Flow、atomicfu |
| 存储 | Room、kotlinx.serialization、JSON5 |
| 网络 | Ktor client/server，OkHttp engine |
| 权限/自动化 | Android AccessibilityService、Shizuku、HiddenApiBypass |
| 图片 | Coil、Telephoto |
| 测试 | JUnit、AndroidX Test、Compose UI test；`selector` 有 JVM 单元测试 |

依赖版本集中在 `gradle/libs.versions.toml`，全局 Android/Kotlin 构建参数在根目录 `build.gradle.kts`。

## 目录地图

```text
app/src/main/kotlin/li/songe/gkd/
  App.kt                  应用启动、全局初始化、元信息
  MainActivity.kt         Compose 根入口、Navigation 3 路由表、全局弹窗挂载
  MainViewModel.kt        主界面状态、Intent/路由/更新等协调
  a11y/                   无障碍事件处理、规则查询上下文、节点转换、运行状态
  data/                   订阅、规则、配置、日志、快照等数据模型和 DAO
  db/                     Room 数据库与迁移
  service/                Android Service、前台服务、悬浮窗、HTTP 服务、快捷开关
  shizuku/                Shizuku 用户服务、自动化模式、AppOps 等
  store/                  设置持久化和全局 Flow
  ui/                     Compose 页面、路由、共享组件、主题
  util/                   通用工具、网络、文件、权限、系统兼容等

selector/src/commonMain/kotlin/li/songe/selector/
  parser/                 选择器语法解析
  property/               属性表达式，如 text/id/vid 比较
  connect/                节点关系表达式，如父子、兄弟、祖先等
  unit/                   选择器逻辑组合
  Selector.kt             对外入口：parse、match、matchContext
  QueryContext.kt         匹配路径上下文
  FastQuery.kt            可被加速的查询条件

hidden_api/src/main/java/
  android/...             Android 隐藏 API 声明
  com/android/...         系统内部接口声明
```

`util/` 不只是杂项工具：订阅文件加载、订阅更新检测、远程订阅拉取、网络可用性检查和本地 HTTP 内存订阅更新主要集中在 `SubsState.kt`、`NetworkUtils.kt`、`NetworkExt.kt`。<!-- updated: SubsState.kt#checkSubsUpdate -->

## 启动与主入口

Android 入口在 `app/src/main/AndroidManifest.xml`：

- `li.songe.gkd.App` 是 `Application`。
- `li.songe.gkd.MainActivity` 是主 Activity。
- 无障碍服务注册名是 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`，它继承 `li.songe.gkd.service.A11yService`。这个包名是有意保留的兼容处理。
- `ScreenshotService`、`StatusService`、`HttpService`、`ButtonService`、`ActivityService`、`EventService`、`TrackService`、`ExposeService` 等承担截图、状态通知、调试服务、悬浮窗和外部调用能力。
- 多个 `TileService` 对应快捷设置开关。

UI 根入口在 `MainActivity.kt`：

- `setContent { AppTheme { NavDisplay(...) } }` 挂载 Compose 界面。
- 所有页面路由集中在 `entryProvider`，例如 `HomeRoute`、`SnapshotPageRoute`、`SubsAppListRoute`、`AppConfigRoute`。
- 页面文件主要在 `ui/` 和 `ui/home/`，公共组件在 `ui/component/` 和 `ui/share/`。

`HttpService` 会启动本地 Ktor 服务，根路径返回加载 `SERVER_SCRIPT_URL` 的调试页面脚本，`/api` 下提供快照获取/删除、截图获取、主动抓取快照、内存订阅更新和远程执行选择器接口。<!-- updated: HttpService.kt#createServer -->

当 HTTP 服务销毁或应用后续启动时，如果 `autoClearMemorySubs` 开启，会删除 `LOCAL_HTTP_SUBS_ID` 对应的内存订阅，避免调试订阅长期残留。<!-- updated: HttpService.kt#clearHttpSubs -->

## 核心运行机制

可以把 GKD 的自动点击链路理解为：

```text
订阅 JSON/本地规则
  -> RawSubscription / RawGroup / RawRule
  -> ruleSummaryFlow 合并订阅、安装应用、AppConfig、SubsConfig、CategoryConfig
  -> ResolvedRule / activityRuleFlow
  -> AccessibilityEvent
  -> A11yRuleEngine 过滤、合并、节流
  -> A11yContext 查询当前窗口节点
  -> selector 模块解析和匹配选择器
  -> ActionPerformer 执行动作
  -> ActionLog / UI / Toast 更新结果
```

关键文件：

- `data/RawSubscription.kt`：订阅原始数据结构、分类、应用规则、全局规则、规则兼容逻辑。
- `data/ResolvedRule.kt`：运行时规则对象，会缓存 `Selector.parse(...)` 结果，并处理前置规则、冷却、最大执行次数、延迟、优先级等状态。
- `util/SubsState.kt`：`ruleSummaryFlow` 合并已启用订阅、应用安装信息、应用配置、订阅组配置和分类配置，再生成 `GlobalRule` / `AppRule`。<!-- updated: SubsState.kt#ruleSummaryFlow -->
- `a11y/A11yRuleEngine.kt`：事件消费、页面识别、规则查询、动作执行的主引擎。
- `a11y/A11yContext.kt`：把 Android 无障碍节点适配给选择器查询。
- `data/ActionPerformer` 相关代码：把规则动作转为具体点击、返回、截图、无动作等执行行为。
- `selector/Selector.kt`：选择器库对外 API，核心方法是 `parse`、`match`、`matchContext`。

事件侧的几个重要点：

- `A11yService.onAccessibilityEvent` 会把事件交给 `A11yRuleEngine.onA11yEvent`。
- 引擎会过滤无效事件、副屏事件、部分输入法事件、自身应用事件，并对高频 `CONTENT_CHANGED` 做节流。
- 事件先进入单线程 `eventDispatcher` 串行消费和合并；耗时节点查询走单线程 `queryDispatcher`；动作后重查、延迟匹配和延迟动作走单线程 `actionDispatcher`，避免事件、查询、动作互相阻塞。<!-- updated: A11yRuleEngine.kt#A11yRuleEngine -->
- 查询前会检查 `storeFlow.value.enableMatch`、当前应用规则、当前服务模式是否仍然有效。
- 匹配成功后，规则会先处理 `matchDelay`/`actionDelay`/冷却/次数等条件，再调用 `performAction`。

`A11yContext` 对 root、child、parent、index 使用缓存，并在应用切换或节点变化时清理旧缓存；当存在优先级规则时，`guardInterrupt` 会在当前规则不再处于优先级状态时中断旧查询。<!-- updated: A11yContext.kt#A11yContext -->

## 数据与状态

Room 数据库定义在 `db/AppDb.kt`，当前数据库版本为 14。实体包括：

- 订阅：`SubsItem`
- 订阅配置：`SubsConfig`
- 分类配置：`CategoryConfig`
- 应用配置：`AppConfig`
- 快照：`Snapshot`
- 动作日志：`ActionLog`
- Activity 日志：`ActivityLog`
- 应用访问日志：`AppVisitLog`
- 无障碍事件日志：`A11yEventLog`

数据库通过 `DbSet` 暴露 DAO，实际文件路径是 `dbFolder.resolve("gkd.db")`。新增表、字段或改字段名时，要同步更新 `@Database(version = ...)` 和 migration。项目已经使用 Room AutoMigration，简单字段变更优先沿用现有方式。

设置和全局状态主要在 `store/`、`util/*Flow`、`a11y/A11yState.kt` 一带。阅读时重点关注 Flow 的来源和收集位置，不要只看 UI 上的开关。

订阅配置会在生成运行时规则前参与过滤：全局组使用 `SubsConfig.GlobalGroupType` 覆盖组默认启用状态；应用组启用优先级是用户组配置、分类配置、分类默认、组默认；应用本身还会受 `AppConfig` 和安装信息过滤。<!-- updated: SubsState.kt#getGroupEnable -->

运行时规则范围由 `ActivityRule` 收敛：它按当前 `topActivity` 从 `ruleSummaryFlow` 取当前应用规则和全局规则，再通过 `AppRule.matchActivity` / `GlobalRule.matchActivity` 过滤应用、Activity、版本和 exclude 配置。<!-- updated: A11yState.kt#ActivityRule -->

## 选择器模块

`selector` 是独立的 Kotlin Multiplatform 模块，适合单独阅读和测试。

入口：

- `Selector.parse(source)`：解析选择器文本。
- `Selector.match(node, transform, option)`：匹配并返回目标节点。
- `Selector.matchContext(node, transform, option)`：匹配并返回带路径的 `QueryResult`。

结构：

- `parser/` 负责把字符串读成 AST/表达式。
- `property/` 负责属性比较表达式。
- `connect/` 负责节点关系。
- `unit/` 负责选择器组合、非、逻辑表达式。
- `Transform<T>` 负责把平台节点抽象成选择器可读取的属性、父子关系等。

`FastQuery` 是选择器剪枝用的候选条件类型，目前包括 `Id`、`Vid`、`Text`，其中 `Text` 支持等于、开头、包含、结尾四类比较。<!-- updated: FastQuery.kt#FastQuery -->

`PropertySegment` 只会把可直接定位的表达式提取为 `FastQuery`：`id ==`、`vid ==`，以及 `text ==`、`text ^=`、`text *=`、`text $=`；空字符串、非字符串字面量和其他属性不会进入快速查询列表。<!-- updated: PropertySegment.kt#expToFastQuery -->

当 `MatchOption.fastQuery` 开启且选择器存在快速查询条件时，`Transform.querySelectorAll` 和后代连接场景中的 `ConnectWrapper` 会调用 `traverseFastQueryDescendants`，而不是先全量遍历所有后代；Android 端的 `A11yContext` 会把它映射到 `findAccessibilityNodeInfosByViewId` 或 `findAccessibilityNodeInfosByText`。<!-- updated: A11yContext.kt#A11yContext -->

如果改选择器语法或匹配性能，优先补 `selector/src/jvmTest/kotlin/li/songe/selector/` 下的测试。

## 常见贡献入口

| 我想做什么 | 优先看哪里 |
| --- | --- |
| 改首页/设置/列表 UI | `app/src/main/kotlin/li/songe/gkd/ui/`、`ui/home/`、`MainActivity.kt` 路由表 |
| 新增页面 | 新建 `Route` + `Page`，然后在 `MainActivity.kt` 的 `entryProvider` 注册 |
| 改订阅解析或规则字段 | `data/RawSubscription.kt`、`data/ResolvedRule.kt`、相关 UI 编辑页 |
| 改匹配逻辑 | `a11y/A11yRuleEngine.kt`、`a11y/A11yContext.kt`、`selector/` |
| 改选择器语法 | `selector/src/commonMain/.../parser`、`property`、`connect`、`unit` |
| 改动作执行 | `data/ActionPerformer` 相关代码、`a11y/A11yRuleEngine.kt` |
| 改截图/快照 | `service/ScreenshotService.kt`、`data/Snapshot.kt`、`ui/SnapshotPage.kt` |
| 改 HTTP 调试服务 | `service/HttpService.kt` |
| 改 Shizuku/自动化模式 | `shizuku/`、`a11y/A11yCommonImpl.kt`、`service/A11yService.kt` |
| 改数据库 | `db/AppDb.kt`、`data/*` DAO、Room schema |
| 改构建/发版 | `build.gradle.kts`、`app/build.gradle.kts`、`.github/workflows/` |

## 本地开发命令

Windows 下按本仓库 Codex 指引优先使用 PowerShell 7。常用 Gradle 命令：

```powershell
.\gradlew.bat app:assembleGkdDebug
.\gradlew.bat selector:jvmTest
.\gradlew.bat app:testGkdDebugUnitTest
.\gradlew.bat app:connectedGkdDebugAndroidTest
```

说明：

- CI 使用 Java 21；本地也建议使用 JDK 21。
- `app` 有 `gkd` 和 `play` 两个 flavor，日常开发一般使用 `gkdDebug`。
- Release 签名通过 Gradle properties 注入。不要把 keystore、密码、token 写进仓库。
- `app/build.gradle.kts` 会读取 Git commit/tag 生成版本元信息，所以构建目录需要是正常 Git 仓库。

## 提交前检查

根据改动范围选择验证：

```powershell
# 选择器、规则匹配库
.\gradlew.bat selector:jvmTest

# App 普通单元测试
.\gradlew.bat app:testGkdDebugUnitTest

# 能编出调试包
.\gradlew.bat app:assembleGkdDebug

# 需要设备/模拟器的 Android 测试
.\gradlew.bat app:connectedGkdDebugAndroidTest
```

改数据库时额外检查：

- `AppDb` 版本号是否递增。
- 是否需要 `AutoMigrationSpec`。
- 旧数据是否可以迁移，不能迁移时要明确说明风险。

改无障碍/自动化时额外检查：

- 是否会误处理自身应用事件。
- 是否会在屏幕关闭、输入法、副屏、多窗口场景误触发。
- 是否保持事件、查询、动作三个调度器的串行假设。
- 是否记录必要日志，避免静默失败。

## 代码风格与约定

- Kotlin 使用官方风格，见 `gradle.properties` 的 `kotlin.code.style=official`。
- Compose 页面通常以 `Route` + `Page` + 可选 `Vm` 组织。
- 数据类和 DAO 常放在同一个 `data/*.kt` 文件里，DAO 作为实体内部接口出现。
- 全局状态多用 `Flow`/`MutableStateFlow` 暴露，UI 侧通过 `collectAsState` 或自定义 helper 收集。
- 规则运行时对象倾向使用缓存和原子状态，避免在高频事件里重复解析选择器。
- Git commit 风格从近期历史看以 `fix:`、`perf:`、`chore:` 为主。

## 建议阅读路线

第一次读源码可以按这个顺序：

1. `README.md`：确认产品目标和用户语义。
2. `settings.gradle.kts`、`gradle/libs.versions.toml`、`app/build.gradle.kts`：确认模块和技术栈。
3. `app/src/main/AndroidManifest.xml`：确认 Android 组件和权限。
4. `MainActivity.kt`：理解 UI 路由和全局弹窗。
5. `data/RawSubscription.kt`、`data/ResolvedRule.kt`：理解规则从配置到运行时的转换。
6. `util/SubsState.kt`：理解订阅、用户配置和安装应用信息如何汇总成运行时规则。<!-- updated: SubsState.kt#ruleSummaryFlow -->
7. `service/A11yService.kt`、`a11y/A11yRuleEngine.kt`：理解事件到动作的核心链路。
8. `selector/property/PropertySegment.kt`、`selector/Transform.kt`、`a11y/A11yContext.kt`：理解 FastQuery 如何从选择器落到 Android 节点查询。<!-- updated: A11yContext.kt#A11yContext -->
9. `a11y/A11yContext.kt` 和 `selector/Selector.kt`：理解选择器如何落到无障碍节点。
10. `db/AppDb.kt`：理解持久化边界。

读的时候建议先画一条具体规则的路径：它从订阅 JSON 进入，如何变成 `ResolvedRule`，如何在当前 App 的事件里被匹配，最后怎样执行动作并写日志。这条线跑通后，其他模块会自然归位。
