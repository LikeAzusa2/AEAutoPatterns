# AEAutoPatterns 开发交接

**基线（2026-11-18）**：GitHub `LikeAzusa2/AEAutoPatterns`，`main` 提交 `2a7f92f`，回滚标签 `rollback-terminal-selection-2026-11-18`。

**环境**：Minecraft 1.21.1、Java 21、NeoForge 21.1.169、AE2 19.2.13、GuideME 21.1.1；JEI 可选。网络协议为 `4`，客户端和服务端须同步更新。终端是装在线缆上的 AE Part，准星对准后按 G 查看指南。

## 当前行为

- **分析与生成**：服务端分步解析配方、回溯循环路线，复用网络和输出栏已有样板，继续补齐下游依赖。共享依赖只编码一次；每张消耗一个空白样板。JEI 只辅助排序，服务端验证配方。
- **原料调整**：同一配方内当前 `AEKey` 相同的输入合成一项；替换列表支持搜索，只提供组内所有槽位都接受的候选，选中后一起替换。
- **供应器选择**：显示名称不同于自身默认名称的供应器按同名合并，包括继承任意相邻机器名称的情况；默认名称各自显示。勾选和仅合成设置同步给组内在线可见的设备，存档和上传仍按实际设备 ID。
- **兼容边界**：工作台、切石机、锻造台使用 AE 原生样板。处理样板可选，由适配器验证；只编码确定输入和输出，概率产物和机器条件只作提示。Mekanism 化学品需要同时安装 Mekanism 与 Applied Mekanistics。

## 从哪里改

| 入口 | 职责 |
| --- | --- |
| `logic/AnalysisSession`、`logic/DependencyPlanner`、`logic/AnalysisScheduler` | 分步建图、循环回溯、共享预算 |
| `part/AutoPatternTerminalPart`、`logic/PatternDeployment` | 终端执行与存档、供应器分组与上传 |
| `menu/AutoPatternMenu`、`network/TerminalPackets` | 服务端动作校验、界面和配方树同步 |
| `client/IngredientSelectionView`、`client/ProviderSelectionView` | 原料弹窗、供应器选择 |
| `integration/RecipeAdapterProvider` | 通过 `ServiceLoader` 接入机器适配器 |

## 验证与下一步

- `./gradlew build`（Windows：`gradlew.bat build`）已通过，现有 **42 项单测通过**。JAR：`build/libs/aeautopatterns-1.0.0.jar`。服务端集成测试入口：`gradlew.bat runGameTestServer`。
- 优先在游戏内复测两条新路径：重复原料批量替换后重新分析及编码；用原版与第三方供应器验证默认名独立、相邻机器同名分组、批量设置与实际上传一致。

