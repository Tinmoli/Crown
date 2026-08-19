# Crown 实现路线图与 AI 接手说明

> 最后更新：2026-08-18（仅维护 26.x）  
> 工作目录：`E:\XiaoMu\Crown`  
> 权威功能规格：`DESIGN.md`  
> 本文件记录实际完成状态；每次实现、验证或发现阻断后必须同步更新。

## 1. 项目目标

Crown 是纯服务端 Fabric 称号商城/仓库模组，参考 PlayerTitle 插件的交互，
但不实现 Crown 不具备的 BUFF、粒子或属性称号功能。

目标 Minecraft 版本必须独立构建：

- `26.1`
- `26.1.1`
- `26.1.2`
- `26.2`

核心能力：命令与虚拟箱子 GUI 购买/管理称号、Mint 经济、称号币、
SQLite/MySQL、权限 API/LuckPerms 与 OP 回退、称号卡、管理员管理、审计、
Placeholder、可选原版聊天/TAB/头顶显示。

## 2. 不可破坏约束

1. 客户端不要求安装 Crown。
2. 所有 JDBC 操作必须在存储执行器上运行，完成回调切回服务端主线程。
3. Mint 是硬依赖，API 主版本必须为 1。
4. 玩家输入和动态变量必须按字面量注入，不允许二次解析格式变量。
5. 原版聊天模式不得替换、伪造或重新签名 `PlayerChatMessage`；只能修改
   原版 `ChatType.Bound` 的显示名参数。
6. 头顶显示不得抢占其他模组创建的 scoreboard team。
7. 每个版本目录包含完整的 Fabric 层代码；Minecraft 无关的业务逻辑放在
   `common/*`，版本目录之间不共享 Java 源集。
8. 不得宣称某个版本可发布，除非该版本完成 `build` 和服务端启动验证。

## 3. 显示策略决策

每个渠道独立支持三种模式：

```yaml
display:
  channels:
    chat: "placeholder"
    tab: "placeholder"
    nametag: "placeholder"
```

- `placeholder`（默认）：Crown 不接管原版显示，外部聊天/TAB/计分板模组
  使用 Crown Placeholder。
- `vanilla`：Crown 用原版服务端机制直接显示。
- `disabled`：Crown 不主动显示；Placeholder 变量仍保持可读取。

旧 `display.placeholder-first` 和 `display.direct.*.enabled` 只用于读取旧配置：

- 旧 `enabled=true` → `vanilla`
- 旧 `enabled=false` 且 `placeholder-first=true` → `placeholder`
- 其他情况 → `disabled`

## 4. 模块维护边界

- `common/domain`：纯领域模型和称号文本解析。
- `common/config`：配置模型、解析、同步、语言和 GUI 模板。
- `common/storage`：SQLite/MySQL、Schema、迁移、审计与仓储。
- `common/runtime`：购买、经济、缓存、并发与生命周期。
- `versions/<mc>`：该版本完整的 Fabric 生命周期、平台适配、命令、GUI、显示、
  Gradle、fabric.mod.json 和 Mixin。

新增 Minecraft 版本时，复制最近的完整版本模块，只调整依赖和经过真实 JAR
核对后的 API/Mixin 差异；修改后运行版本源代码一致性检查。

## 5. 实现清单

### P0 显示渠道模式

- [x] 确定 `placeholder | vanilla | disabled` 三态设计。
- [x] 新增 `DisplayMode` 配置模型。
- [x] 新增 `display.channels.chat/tab/nametag`，默认 placeholder。
- [x] 运行时聊天、TAB、头顶仅在 VANILLA 模式接管。
- [x] 保留旧配置解析回退。
- [x] 为三态解析、非法值和旧配置映射增加单元测试。
- [x] 为旧 config.yml 自动写入新 channels 并备份做迁移测试。

### P1 原版显示适配

- [x] 26.1/26.1.1/26.1.2/26.2：聊天 Mixin 独立注册。
- [x] 聊天仅装饰 `ChatType.Bound.name`，不修改签名消息。
- [x] 26.x TAB `getTabListDisplayName` 与 PlayerInfo 更新实现。
- [x] 头顶 scoreboard team 冲突保护。
- [x] 四个 26.x 目标方法已通过真实 Minecraft JAR `javap` 核对。
- [x] 26.x 四个版本 `compileJava` 全部通过，聊天/TAB Mixin 类均生成。
- [ ] 26.x Mixin 运行时 apply 与真实服务端行为验证。
- [ ] 真实服验证签名状态、TAB 刷新、重载和其他显示模组共存。

### P2 GUI 与本地化

- [x] 默认 GUI 使用 PlayerTitle 风格的中部 4×7 内容区和底部导航。
- [x] 默认 GUI 升级到 config-version 2，v1 升级前备份。
- [x] GUI 文案独立于语言文件，标题、按钮、Lore 和 GUI 动态文本均由各自
  `config/crown/gui/*.yml` 配置；命令和聊天反馈继续使用语言文件。
- [x] 玩家自定义称号入口、购买确认、删除确认已接通。
- [x] 管理员仓库 GUI 已实现并接入 `/crown view`。
- [x] 管理员商品创建按钮已接入聊天输入草稿会话。
- [x] 管理员商品 GUI 完成期限、库存、限购、销售时间和删除的可点击编辑流程。
- [x] 管理员商品详情的文本、前缀、后缀、购买权限已接入受限聊天编辑会话；
  每次仅编辑一个 `text=`、`prefix=`、`suffix=` 或 `permission=` 字段，写入仍
  复用原子配置编辑、运行时重载和审计链。
- [x] 管理员商品详情的支付方式与价格已接入受限聊天编辑会话：`free`、
  `title_coin=<正整数>` 或 `mint=<正数价格>`；Mint 货币统一使用全局
  `config.yml` 的 `purchase.mint-currency`，商品只保存 `payment-options`。
- [x] `/crown reload` 已接入列表页 GUI 会话刷新注册表；确认页和聊天输入会话不会被强制恢复。
  仍需真实服验证关闭、跳转、断线和重载竞态。
- [ ] 真实服检查空页、长 lore、分页边界和所有按钮。

当前管理员商品详情审计：slot 20（启用）、22（主手图标）、24/31（期限与销售）、
29（支付）、33（文本/权限）和 40（删除）均有回调。期限使用
`duration=permanent|limited:<1-36500>`；销售时间使用 UTC ISO-8601 或 `none`；
库存和个人限购使用正整数或 `unlimited`。删除经过二次确认，只移除商品定义，
不会删除玩家历史仓库条目。

### P3 Placeholder 与显示变量

- [x] 已有 title/prefix/text/suffix 等变量。
- [x] 已实现 `crown:title_expires`。
- [x] Placeholder 只读内存缓存，不在解析线程访问数据库。
- [x] 写 README 中 Placeholder API 和 Chat/TAB 模组配置示例。
- [ ] 未安装 Placeholder API 时验证启动和所有 vanilla 模式。

### P4 命令、管理与权限

- [x] 玩家商城、购买、仓库、佩戴、卸下、删除、自定义、币、卡命令。
- [x] 管理员发放、回收、期限、选择、查看、商品、存储、审计命令。
- [x] permissions-api/LuckPerms 优先，原版 OP 等级回退。
- [ ] 将所有 GUI 可执行动作与命令能力做自动集合审计。
- [ ] 实机验证无 LP、仅 permissions-api、安装 LP 三种权限环境。

### P5 经济、存储与恢复

- [x] Mint 支付网关和主版本检查。
- [x] 称号币账本、购买订单状态机和恢复逻辑。
- [x] SQLite 默认、MySQL、Schema 迁移和存储迁移。
- [x] 管理员写操作审计。
- [ ] Mint 正常、余额不足、超时、提供者异常、存储失败端到端测试。
- [ ] SQLite 与 MySQL 真实后端迁移及摘要一致性测试。

### P6 多版本构建

- [x] 新增 `-PcrownVersions=<版本列表|none>`，单版本任务不再配置其他版本。
- [x] `26.1` build（JAR 已生成并检查内容；尚需真实服启动验证）。
- [x] `26.1.1` build（JAR 已生成并检查内容；尚需真实服启动验证）。
- [x] `26.1.2` build（JAR 已生成并检查内容；尚需真实服启动验证）。
- [x] `26.2` build（JAR 已生成并检查内容；尚需真实服启动验证）。
- [x] `buildAllVersions` 产出四个名称明确且互不覆盖的 JAR（当前 Loom
  配置的发布任务是 `jar`，不是 `remapJar`）。
- [ ] 每个版本执行服务端 smoke test。

版本选择器已经解除单版本构建之间的 Gradle 配置耦合。1.21.11 与其
SGUI 官方命名空间适配模块已按维护范围移除；当前只维护四个 26.x 版本。

26.x 构建约定：

- 每个 `versions/<mc>/build.gradle` 只声明该版本的 Minecraft、Fabric API、
  SGUI、Placeholder API 和权限 API 差异。
- 公共的 Java 25、嵌入依赖、资源替换和 smoke run 放在
  gradle/crown-26-version.gradle。
- 新增 26.x 版本时复制最近版本的完整源码目录并修改版本值；只有发生原版
  API/Mixin 差异时才修改该版本目录中的对应文件。
- 2026-08-17：已移除 `versions/shared-26` 和 `versions/shared`；四个
  `versions/26.*` 目录各自保留完整 Fabric 源代码。

## 6. 当前已完成内容

以下内容已经有源码实现，不应重复重写：

- 四个独立版本目录：`versions/26.1`、`26.1.1`、`26.1.2`、`26.2`；
  `versions/shared`、`versions/shared-26` 和 `1.21.11` 已移除。
- `common/domain`、`common/config`、`common/storage`、`common/runtime` 的
  领域、配置、存储、经济、购买、恢复、权限和审计基础能力。
- Mint 经济强依赖、称号币、SQLite/MySQL、称号卡、订单恢复、管理员命令和
  玩家/管理员仓库 GUI。
- 默认 GUI v2、PlayerTitle 风格布局、中英文语言键同步和旧 GUI 配置备份迁移。
- 默认显示模式为每渠道 `placeholder`，支持 `placeholder | vanilla | disabled`；
  vanilla 聊天只装饰 `ChatType.Bound.name`，不修改或重签名玩家消息。
- 文本/权限编辑会话：`AdminTitleTextEditSessions`，支持单条
  `text=...`、`prefix=...`、`suffix=...`、`permission=...`。
- 支付编辑会话：`AdminTitlePaymentEditSessions`，支持 `free`、
  `title_coin=<正整数>`、`mint=<正数价格>`，以整个 `payment-options` 映射原子写入。
- 上述聊天会话均有权限检查、互斥、取消、超时、断线清理，并复用
  `CrownTitleAdminCommands.updateFromGui` 的原子写入、运行时重载和审计。
- `CrownGuiSessions` 已登记主菜单、商城、玩家仓库、管理员商城、管理员仓库，
  `/crown reload` 会尝试刷新这些列表页；购买/删除确认和聊天输入不会自动恢复。
- `buildAllVersions` 已生成四个独立 JAR，`dist/` 只收集四个版本产物。

## 7. 当前未完成内容

按优先级：

1. **四版本真实运行验证**：启动每个目标版本，验证 Mixin apply、签名聊天、TAB
   更新、nametag 冲突保护、GUI 点击、聊天会话和热重载。
2. **自动审计测试**：检查 GUI action、语言键、Placeholder、命令权限和四版本
   源码同步，避免新增版本时漏复制公共 Fabric 文件。
3. **真实 MySQL 集成验证**：需要外部测试数据库；当前未将源码级 MySQL
   方言/迁移覆盖表述为真实后端测试通过。

## 8. 已知风险与注意事项

- 当前 Git 状态会显示旧 `shared` 路径删除、四版本源码新增，这是目录迁移结果，
  不要用 `git reset`、`git checkout` 或批量清理来“修复”它；先审阅后提交。
- `IMPLEMENTATION_ROADMAP.md`、四版本源码和配置改动目前可能尚未提交；不要丢弃
  用户已有改动。
- `common/runtime/src/main/java/dev/xiaomu/crown/runtime/purchase/UnifiedPurchaseService.java`
  中的 `UnsupportedOperationException("Purchase settings are not persisted separately")`
  是购买设置快照的保护性分支，不要在没有调用链证据时直接删除；购买端到端测试仍需补充。
- `26.1.1`、`26.1.2`、`26.2` 的历史 JAR 已存在，但新的编辑会话修改后必须重新
  构建，不能把旧 JAR 当作当前源码验证结果。
- 版本目录源码完全独立。修改 `versions/26.1` 中的非 Mixin Fabric 文件后，必须
  同步到其他三个版本，并运行 `verifyVersionSources` 或等价哈希检查；Mixin、
  `fabric.mod.json` 和版本依赖允许不同。

## 9. 下一位 AI 的建议起点

1. 先读取 `DESIGN.md` 和本文件，再运行 `git status --short`；不要丢弃当前未提交改动。
2. 确认四个版本目录存在且 `versions/shared*` 不存在。
3. 先实现第 1 项期限会话，参考 `AdminTitlePaymentEditSessions.java`，不要创建共享 Java 源集。
4. 同步到四个版本后运行公共测试和至少一个版本编译，再更新本文件。
5. 在线运行四个版本各自的 `build`，最后执行 `buildAllVersions` 和真实服务端 smoke test。
6. 每完成一个项目，立即更新本文件的复选框、验证命令、日期和失败原因。

推荐命令（PowerShell）：

```powershell
$g = 'C:\Users\Tinmoli\.gradle\wrapper\dists\gradle-9.4.0-bin\local-crown\gradle-9.4.0\bin\gradle.bat'
& $g '-PcrownVersions=none' ':common:config:test' '--no-daemon' '--console=plain'
& $g '-PcrownVersions=26.1' ':versions:26.1:compileJava' '--no-daemon' '--console=plain'
& $g '-PcrownVersions=26.1.1' ':versions:26.1.1:compileJava' '--no-daemon' '--console=plain'
& $g '-PcrownVersions=26.1.2' ':versions:26.1.2:compileJava' '--no-daemon' '--console=plain'
& $g '-PcrownVersions=26.2' ':versions:26.2:compileJava' '--no-daemon' '--console=plain'
& $g 'buildAllVersions' '--no-daemon' '--console=plain'
```

## 10. 最近验证记录

### 2026-08-17：基线架构与四版本产物

- 在线执行 `gradle -PcrownVersions=none :common:config:test --no-daemon`，通过，
  6 个任务执行成功。
- 26.1 的 `compileJava` 通过；聊天与 TAB Mixin 类生成。
- 随后的基线四版本隔离 `compileJava` 与 `buildAllVersions` 通过；检查了四个
  JAR 的 Minecraft 依赖、`crown.mixins.json`、Mixin class、嵌套依赖和默认资源。
  这组结果证明当时提交状态的四版本构建链可用，不代表后续编辑会话改动后的
  当前源码已经重新完成四版本构建。
- `dist/` 当时只收集 `26.1`、`26.1.1`、`26.1.2`、`26.2` 四个 JAR，文件名与
  `fabric.mod.json` 的 Minecraft 依赖一致。
- 按维护范围移除 `1.21.11` 与 `1.21.11-sgui`；不再维护 Java 21/official
  namespace 构建链。
- 通过真实 Minecraft JAR `javap` 核对四个版本的聊天广播重载和
  `ServerPlayer#getTabListDisplayName()`。
- GUI 文案审计：默认 `gui/*.yml` 不再引用语言键；标题、按钮、Lore 和动态
  GUI 文本均由各自 GUI 配置提供。命令、聊天反馈和系统错误仍由语言文件提供。
- `git diff --check` 曾发现并修复 `CrownPurchaseConfirmGui.java` 尾随空格。

### 2026-08-17：管理员商品编辑接入后的验证

- slot 33 接入 `AdminTitleTextEditSessions`，支持单条 `text=`、`prefix=`、
  `suffix=` 或 `permission=` 输入；权限、互斥、取消、超时、断线清理和成功后
  重开详情页均已实现。四个版本对应源码哈希一致。
- slot 29 接入 `AdminTitlePaymentEditSessions`，支持 `free`、
  `title_coin=<正整数>` 和 `mint=<正数价格>`；`payment-options` 映射原子写入，
  四个版本对应编辑源码哈希一致。
- 支付编辑接入后，在线执行
  `gradle -PcrownVersions=26.1 :versions:26.1:compileJava :common:config:test
  --no-daemon`，通过。该结果只覆盖 26.1 和公共配置测试。

### 2026-08-18：当前工作树审计与未完成项确认

- 确认 `versions/26.1`、`26.1.1`、`26.1.2`、`26.2` 存在，
  `versions/shared` 与 `versions/shared-26` 均不存在。
- 期限、销售时间、库存/限购编辑会话和商品删除确认流程均已接入管理员商品
  详情页；当前仍需真实服务器验证点击流程、重载竞态和异常恢复。
- 当前四个 `build/libs` 中存在 2026-08-17 生成的历史 JAR；这些 JAR 不能作为
  支付编辑接入后当前源码的验证结果，必须重新执行对应版本构建。
- 曾尝试在线配置 `26.1.1`、`26.1.2`、`26.2` 的隔离 `compileJava`，Loom 在
  项目配置期下载 Minecraft 元数据失败，未进入 Java 编译；这是网络/上游下载

### 2026-08-18：管理员商品 GUI 完成与最终源码审计

- 新增四版本独立的 `AdminTitleSaleEditSessions`，支持期限、UTC 销售窗口、库存
  和个人限购编辑；联动字段通过 `TitleCatalogEditor.setAll` 原子更新。
- 商品详情 slot 24/31 已接入编辑会话，slot 40 新增删除二次确认；删除写入
  管理员审计且不会删除玩家历史仓库条目。
- `verifyVersionSources` 通过，四版本共 35 个非 Mixin Fabric 源文件一致。
- 公共 `check` 和四个版本 `compileJava` 全部通过；中英文语言 JSON 使用 UTF-8
  解析器验证通过，`git diff --check` 通过。
- 真实 Fabric smoke test、Mixin 运行时行为和真实 MySQL 迁移仍需外部环境，未
  将其标记为源码测试已通过。