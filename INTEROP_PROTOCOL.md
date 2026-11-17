# AE Auto Patterns 第三方引用协议

本文件定义第三方 NeoForge 模组如何声明并引用 AE Auto Patterns 的公开适配接口。它是兼容约定，不修改 AE2、Mekanism 或其他模组的源码和接口。

## 第三方模组声明

在第三方模组的 `META-INF/neoforge.mods.toml` 中声明可选或必需依赖：

```toml
[[dependencies.yourmodid]]
modId="aeautopatterns"
type="optional" # 只有安装时才启用联动
versionRange="[1.0.0,2.0.0)"
ordering="AFTER"
side="BOTH"
```

如果该模组没有 AE Auto Patterns 就不能工作，将 `type` 改为 `required`。推荐在 Gradle 中使用 `compileOnly` 引用本项目 JAR，运行时由 NeoForge 模组加载器提供：

```gradle
dependencies {
    compileOnly files("libs/aeautopatterns-1.0.0.jar")
    compileOnly "maven.modrinth:ae2:19.2.13"
}
```

公开 API 入口为 `com.likeazusa2.aeautopatterns.api.AEAutoPatternsApi`，当前 `API_VERSION` 为 `1`。适配器接口位于 `com.likeazusa2.aeautopatterns.integration`；不要引用 `blockentity`、`menu`、`client` 或 `logic` 包中的实现类。

## 模组声明

- 模组 ID：`aeautopatterns`
- 加载器：NeoForge 4+
- Minecraft：1.21.1
- 必需依赖：NeoForge、Minecraft、AE2 19.2.x
- 可选依赖：JEI、Mekanism、Applied Mekanistics (`appmek`)、Create、Thermal、Immersive Engineering、PneumaticCraft、Modern Industrialization
- 可选依赖全部使用 NeoForge `type="optional"` 声明。未安装时不加载对应桥接，也不会改变内置工作台配方功能。

## AE2 设备互操作

样板上传和样板查询只使用 AE2 公共 API：`IGrid`、`PatternContainer`、`InternalInventory`、`PatternDetailsHelper` 和 `GenericStack`。只向当前网络中由 `PatternContainer` 暴露的样板库存写入，不扫描或修改普通存储中的样板物品。

第三方设备只要实现 AE2 的 `PatternContainer`，就可以被自动输出功能发现。设备断网、不可见、没有库存或库存已满时，终端保留样板并等待，不绕过设备限制。

## 配方适配器协议

第三方适配器通过 Java `ServiceLoader` 注册 `RecipeAdapterProvider`，服务文件为：

```text
META-INF/services/com.likeazusa2.aeautopatterns.integration.RecipeAdapterProvider
```

提供者必须：

1. 返回稳定的 `adapterId()` 和非负 `priority()`；
2. 只在目标模组已加载且 API 可用时返回适配器；
3. 不能在类初始化阶段直接引用可选模组类，避免缺少依赖时崩溃；
4. 对无法确定输入、输出、概率或机器条件的配方返回“不支持”，不能猜测编码；
5. 保持 `RecipeAdapter` 的服务端权威校验和 AE 样板解码验证；
6. 将异常限制在当前候选适配器内，不阻断其他适配器。

适配器优先级只用于候选排序，不覆盖玩家已选择的路线。重复候选按适配器优先级和稳定配方 ID 选择。适配器版本变化、数据包重载或网络拓扑变化后，已有任务必须重新分析。

## 许可与再分发

本项目原始 Java 源码采用 LGPL-3.0-or-later。美术和其他非代码资源不授予开源再利用许可，详见 `ART_ASSETS_NOTICE.md`。AE2、JEI、Mekanism、Applied Mekanistics 及其他模组的代码、商标和资源仍归各自作者所有；本项目只依赖其公开 API，不重新打包或再分发它们的实现。第三方适配器应遵守其目标模组的许可证，并在自身发行包中保留必要的版权和许可证文本。

协议变更通过版本号和本文件记录；增加适配器不改变已有 `RecipeAdapterProvider` 方法的含义。
