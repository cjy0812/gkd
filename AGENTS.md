# AGENTS.md

本文件适用于整个仓库。如本文件与更深层目录中的 `AGENTS.md` 冲突，以更深层文件为准。

## 项目概览

GKD 是一个 Android 应用，通过无障碍服务或自动化能力读取当前界面节点，使用高级选择器匹配目标节点，再按订阅规则执行点击、返回、截图等动作。

开始修改前，先阅读 `docs/source-onboarding.md`。它是本仓库的源码导览，包含核心链路、主要入口、规则流转和验证命令。

## 技术栈与模块

- `app`：Android 应用主体，包含 Compose UI、无障碍/自动化、Room、订阅管理、日志、截图、HTTP 调试服务。
- `selector`：Kotlin Multiplatform 选择器解析与匹配库，可用于 JVM 和 JS。
- `hidden_api`：Android 隐藏 API 的编译期声明，供 `app` 以 `compileOnly` 方式引用。
- 技术栈：Kotlin、Android Gradle Plugin、Jetpack Compose、Material 3、Navigation 3、Coroutines/Flow、Room、Ktor、Shizuku。
- 日常开发默认使用 `gkdDebug` flavor。
- CI 使用 Java 21，本地建议 JDK 21。

## 重要源码入口

- `docs/source-onboarding.md`：源码结构、核心机制和阅读路线。
- `app/src/main/AndroidManifest.xml`：Android 组件、权限、Activity、Service、Provider 声明。
- `app/src/main/kotlin/li/songe/gkd/MainActivity.kt`：Compose 根入口、Navigation 3 路由表、全局弹窗挂载。
- `app/src/main/kotlin/li/songe/gkd/a11y/A11yRuleEngine.kt`：无障碍事件过滤、规则查询、动作执行主流程。
- `app/src/main/kotlin/li/songe/gkd/a11y/A11yContext.kt`：Android 无障碍节点到选择器 `Transform` 的适配。
- `app/src/main/kotlin/li/songe/gkd/util/SubsState.kt`：订阅加载、订阅更新、规则汇总和运行时规则生成。
- `selector/src/commonMain/kotlin/li/songe/selector/Selector.kt`：选择器库对外入口。

## 开发与验证命令

优先使用 PowerShell 7：

```powershell
.\gradlew.bat app:assembleGkdDebug
.\gradlew.bat selector:jvmTest
.\gradlew.bat app:testGkdDebugUnitTest
.\gradlew.bat app:connectedGkdDebugAndroidTest
```

按改动范围选择验证：

- 修改 `selector` 语法、匹配或性能：至少运行 `.\gradlew.bat selector:jvmTest`。
- 修改 Android 应用逻辑：至少运行 `.\gradlew.bat app:testGkdDebugUnitTest` 或 `.\gradlew.bat app:assembleGkdDebug`。
- 修改需要设备能力的功能：使用 `.\gradlew.bat app:connectedGkdDebugAndroidTest`，需要连接设备或模拟器。
- 只改 Markdown 文档时，不需要运行 Gradle 测试，但要检查 Markdown 标题和 `git status --short`。

## 修改约定

- 保持 Kotlin official style，见 `gradle.properties` 的 `kotlin.code.style=official`。
- 不做无关格式化、大范围重排或顺手重构。
- 优先沿用现有目录结构、命名方式、Flow 状态管理方式和 Compose 页面组织方式。
- Compose 页面通常以 `Route` + `Page` + 可选 `Vm` 组织，新页面需要在 `MainActivity.kt` 的 `entryProvider` 注册。
- 数据类和 DAO 常放在同一个 `data/*.kt` 文件里，DAO 通常作为实体内部接口出现。
- 数据库变更必须同步 `AppDb` 版本、Room migration 和相关 schema 配置。
- 选择器语法、匹配语义或 FastQuery 剪枝改动，优先补充 `selector/src/jvmTest/kotlin/li/songe/selector/` 下的 JVM 测试。
- 尊重已有未提交改动，不回滚用户修改；如遇到与任务相关的脏改，先读懂并在其基础上修改。

## 安全与权限注意事项

- 不硬编码签名、token、keystore、密码或其他凭据。
- Release 签名通过 Gradle properties 注入，不要提交任何签名材料。
- 修改无障碍/自动化逻辑时，必须关注误触发、副屏、输入法、锁屏、多窗口和应用自身事件过滤。
- 修改 `HttpService` 时，注意本地调试接口、内存订阅、快照/截图文件访问和 CORS 行为。
- 修改 Shizuku、隐藏 API、权限或前台服务时，确认 Manifest、系统版本兼容和失败降级路径。
- 删除、迁移或清理本地文件前，必须确认目标路径和影响范围。

## 代码代理工作流

1. 先读 `docs/source-onboarding.md` 和本任务相关入口文件，避免凭印象修改。
2. 用搜索定位目标符号和调用链；只在需要完整上下文时读整文件。
3. 先做最小可验证改动，避免跨模块扩大范围。
4. 按改动风险运行对应验证命令，并在最终回复中说明已运行或未运行的检查。
5. 最终回复要概括改动文件、关键行为变化和验证结果。

