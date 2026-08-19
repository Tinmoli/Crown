# Crown 称号模组设计文档

> 文档状态：待评审  
> 目标版本：Minecraft Fabric `26.1`、`26.1.1`、`26.1.2`、`26.2`
> 项目目录：`E:\XiaoMu\Crown`  
> Mod ID：`crown`  
> Maven Group：`dev.xiaomu.crown`  
> 暂定版本：`0.1.0-SNAPSHOT`  
> 运行环境：服务端；普通客户端无需安装 Crown  
> 强制前置：Fabric API、Mint  
> 可选集成：LuckPerms、Text Placeholder API  
> 默认存储：SQLite；可选存储：MySQL

---

## 1. 项目目标

Crown 是一个 Fabric 服务端称号模组，参考 PlayerTitle 的公开功能和交互形式进行独立实现，不复制其付费或非公开源码。

核心目标：

1. 玩家可以通过命令或箱子 GUI 浏览、购买、管理和佩戴称号。
2. 玩家可拥有多个称号，但任意时刻最多佩戴一个称号。
3. 玩家可以选择：
   - 佩戴服务器默认称号；
   - 佩戴仓库中的普通或自定义称号；
   - 不佩戴任何称号，包括不佩戴默认称号。
4. 管理员可以创建、编辑、停售、删除和发放称号。
5. 称号支持永久、限时、限时销售和限量销售。
6. 支持由玩家在聊天栏输入正文的自定义称号购买。
7. 支持 Mint 经济和 Crown 内置“称号币”两种支付方式。
8. 支持十六进制 RGB、旧式颜色码、格式码和渐变色。
9. 优先通过变量向聊天、TAB、计分板等模组提供称号；也可以在配置中开启 Crown 直接显示。
10. 配置、语言和 GUI 样式支持安全热重载。
- 11. 四个 26.x Minecraft 版本分别构建，禁止跨版本混用 JAR。

---

## 2. 设计原则

### 2.1 服务端优先

- Crown 自身只运行在服务端。
- GUI 使用 SGUI 虚拟箱子实现。
- 普通玩家客户端不需要安装 Crown 或 SGUI。
- 自定义资源包图标不属于首个版本的必要功能，但 GUI 配置预留自定义模型数据字段。

### 2.2 变量优先、直接显示可选

默认情况下，Crown 不主动重写聊天、TAB 或头顶名称，避免与聊天、队伍、计分板和权限模组冲突。

推荐服务器通过 Text Placeholder API 使用 Crown 变量。直接显示功能由服主按位置分别开启。

### 2.3 UUID 作为玩家主键

- 所有玩家数据使用 UUID。
- 玩家名只保存为可更新的索引和管理界面展示字段。
- 玩家改名不会丢失称号、称号币或购买记录。

### 2.4 安全失败

以下操作发生异常时必须拒绝操作，不允许使用不完整或过期状态继续执行：

- Mint Provider 不可用；
- Mint 扣款结果未知；
- 数据库写入失败；
- 配置重载验证失败；
- LuckPerms 查询异常；
- GUI 点击对应的商品已被重载删除或价格发生变化。

### 2.5 不阻塞服务器主线程

- Mint API 是异步 API，禁止在服务器线程调用 `join()` 或 `get()`。
- SQLite 使用专用有界单线程执行器保证写入顺序；MySQL 使用有界连接池和事务执行器。
- 异步任务完成后，如需访问 Minecraft 玩家或 GUI 对象，必须调度回服务器线程。

---

## 3. 功能范围

### 3.1 首个正式版本包含

- 默认称号；
- 普通称号商品；
- 玩家自定义称号商品；
- 普通、限时和永久称号；
- Mint 与称号币支付；
- 免费商品和权限门槛；
- 限时销售；
- 全服库存；
- 单玩家限购；
- 商城 GUI；
- 仓库 GUI；
- 购买确认 GUI；
- 删除确认 GUI；
- 自定义称号确认 GUI；
- 管理员查看玩家仓库 GUI；
- 管理员商品管理 GUI；
- 称号卡；
- 命令管理；
- LuckPerms/原版 OP 权限；
- Text Placeholder API 变量；
- 可选聊天、TAB、头顶直接显示；
- SQLite 与 MySQL 持久化（默认 SQLite）；
- 默认简体中文和英文语言文件；
- 配置、语言、GUI 样式热重载；
- 四个 26.x 版本独立构建；
- 单元测试和真实 Fabric 服务端点烟测试。

### 3.2 暂不包含

- Redis 或跨服数据同步；
- 多个 Crown 服务端实例并发共享同一个 MySQL 数据库；
- Web 管理面板；
- 称号 BUFF；
- 称号粒子；
- 玩家之间交易称号；
- 玩家之间转账称号币；
- 自动退款已删除称号；
- 一个玩家同时佩戴多个称号；
- 强制要求客户端安装 Crown。

---

## 4. 多版本工程结构

采用类似 Mint 的公共模块与 Minecraft 版本适配模块结构：

```text
Crown/
├─ build.gradle
├─ settings.gradle
├─ gradle.properties
├─ gradlew
├─ gradlew.bat
├─ DESIGN.md
├─ README.md
├─ LICENSE
├─ common/
│  ├─ domain/       不引用 Minecraft 的领域模型与校验
│  ├─ config/       YML 配置、JSON 语言和 GUI 样式同步
│  ├─ storage/      SQLite/MySQL、Schema、迁移、仓库和审计
│  └─ runtime/      商城、购买、称号币、订单和业务服务
├─ versions/
│  ├─ 26.1/         完整独立的 Fabric 代码与构建配置
│  ├─ 26.1.1/
│  ├─ 26.1.2/
│  └─ 26.2/
├─ tools/
│  └─ server_smoke.py
└─ dist/
```

### 4.1 版本矩阵

| Minecraft | Fabric API | Java | SGUI | Text Placeholder API |
|---|---:|---:|---:|---:|
| `26.1` | `0.144.4+26.1` | 25+ | `2.0.0+26.1` | `3.0.0+26.1` |
| `26.1.1` | `0.145.4+26.1.1` | 25+ | `2.0.0+26.1` | `3.0.0+26.1` |
| `26.1.2` | `0.155.2+26.1.2` | 25+ | `2.0.0+26.1` | `3.0.0+26.1` |
| `26.2` | `0.155.2+26.2` | 25+ | `2.1.0+26.2` | `3.1.0-beta.1+26.2` |

所有版本要求 Fabric Loader `0.19.3` 或更高版本，以满足 Mint 的运行要求。

公共模块输出 Java 21 字节码；四个 26.x Minecraft 适配模块输出 Java 25 字节码。

### 4.2 独立构建

```bat
gradlew.bat :versions:26.1:assemble
gradlew.bat :versions:26.1.1:assemble
gradlew.bat :versions:26.1.2:assemble
gradlew.bat :versions:26.2:assemble
```

完整构建：

```bat
gradlew.bat buildAllVersions
```

预期产物：

```text
versions/26.1/build/libs/crown-fabric-26.1-<version>.jar
versions/26.1.1/build/libs/crown-fabric-26.1.1-<version>.jar
versions/26.1.2/build/libs/crown-fabric-26.1.2-<version>.jar
versions/26.2/build/libs/crown-fabric-26.2-<version>.jar
```

---

## 5. 运行依赖

`fabric.mod.json` 将 Mint 声明为强制依赖：

```json
{
  "id": "crown",
  "environment": "server",
  "depends": {
    "fabricloader": ">=0.19.3",
    "fabric-api": "*",
    "minecraft": "<由各版本模块填写>",
    "java": "<由各版本模块填写>",
    "mint": ">=0.1.0-"
  },
  "suggests": {
    "luckperms": "*",
    "placeholder-api": "*"
  }
}
```

缺少 Mint 时，由 Fabric Loader 直接拒绝加载 Crown，而不是仅禁用商城。

Crown 编译时依赖：

```groovy
compileOnly "dev.xiaomu.mint:mint-api:0.1.0-SNAPSHOT"
```

Mint API 从 `E:\XiaoMu\Mint` 发布到 Maven Local：

```bat
E:\XiaoMu\Mint\gradlew.bat :common:api:publishToMavenLocal
```

---

## 6. 配置目录

首次启动生成：

```text
config/crown/
├─ config.yml
├─ titles.yml
├─ storage.yml
├─ lang/
│  ├─ zh_cn.json
│  └─ en_us.json
├─ gui/
│  ├─ main.yml
│  ├─ shop.yml
│  ├─ warehouse.yml
│  ├─ purchase-confirm.yml
│  ├─ custom-confirm.yml
│  ├─ delete-confirm.yml
│  ├─ admin-shop.yml
│  └─ admin-warehouse.yml
├─ data/
│  └─ crown.db
└─ backups/
```

### 6.1 配置文件格式与处理原则

配置和语言处理参考 `E:\XiaoMu\Tpa` 的实现方式，并增加完整事务式热重载。

所有可编辑配置统一使用 YML 格式：

- `config.yml`：核心功能、默认称号、称号币、自定义称号、显示与权限；
- `titles.yml`：管理员创建的称号商品；
- `storage.yml`：SQLite/MySQL 连接和迁移设置；
- `gui/*.yml`：所有箱子 GUI 布局和物品样式。

配置规范：

- 文件编码统一为无 BOM 的 UTF-8；
- 默认配置文件的文件头、分区说明和字段注释全部使用简体中文；
- 默认配置由程序中的有序 Schema 生成，保证字段顺序和注释稳定；
- 缺少文件或空文件时创建带中文注释的默认文件；
- 缺少新键时补入默认值和对应中文注释；
- 已存在的合法用户值必须保留；
- 废弃的托管键在版本升级时删除，开放映射中的用户数据不删除；
- 保存配置时按照内置字段顺序重写，并重新插入标准中文注释；
- 配置升级、损坏恢复或自动修复前创建时间戳备份；
- 损坏文件备份后恢复默认，不能静默忽略；
- 写入同目录临时文件后原子替换；
- 每个 YML 配置文件都有独立的 `config-version`；
- 配置版本高于当前程序时拒绝由旧版 Crown 覆盖；
- 数字、布尔值、枚举、时间、资源 ID 和跨文件引用均严格校验；
- 重载先完整读取和验证，再一次性替换运行时快照；
- 任意文件失败时保留全部旧配置，禁止半重载。

`titles.yml` 中的 `titles` 是开放映射，Crown 不会因为默认文件中没有某个商品而删除用户创建的商品。

`gui/` 下的文件允许服主自定义布局、按钮和物品样式，执行 `/crown reload` 即时生效。

### 6.2 语言文件格式与同步

语言文件沿用 Tpa 的 JSON 格式，Crown 默认只内置并生成：

- `zh_cn.json`：简体中文，默认语言；
- `en_us.json`：英文，回退语言。

内置管理 `zh_cn.json` 和 `en_us.json`：

- 保留已有翻译值；
- 补入新增键；
- 删除已废弃的内置键；
- 损坏文件保存为 `.invalid-<timestamp>.bak`；
- 使用临时文件和原子替换；
- 默认不生成其他语言；
- 如果服主自行添加其他语言 JSON，则该文件完全由用户管理，不主动改写；
- 当前语言缺键时先回退 `zh_cn`，再回退 `en_us`，最后显示原始键；
- 重载时清空语言缓存。

语言动态组件使用 Tpa 风格的位置参数：

```json
{
  "purchase.success": "购买成功：%0%",
  "purchase.failed.balance": "余额不足，需要 %0%，当前只有 %1%。"
}
```

玩家名、称号正文和其他动态内容作为字面组件插入，不二次解析格式或变量，避免注入。

---

## 7. 核心配置设计

`config/crown/config.yml` 示例：

```yaml
config-version: 1

language: "zh_cn"

default-title:
  enabled: true
  equip-for-new-player: true
  text: "萌新"
  prefix: "&7["
  suffix: "&7]"
  icon: "minecraft:name_tag"

title-coin:
  name: "称号币"
  symbol: "✦"
  format: "{amount} {name}"
  maximum-balance: 9223372036854775807

custom-title:
  enabled: true

  duration:
    type: "PERMANENT"  # PERMANENT 或 LIMITED
    days: 0

  payment:
    type: "MINT"       # MINT 或 TITLE_COIN
    # Mint 货币 ID 统一使用 config.yml 的 purchase.mint-currency。
    price: "1000.00"

  prefix: "&7["
  suffix: "&7]"
  minimum-length: 1
  maximum-length: 16
  allow-rgb: true
  allow-gradient: true
  allow-formatting: true
  input-timeout-seconds: 60
  cancel-keywords:
    - "取消"
    - "cancel"
  forbidden-words: []

display:
  # 每个渠道独立选择：placeholder、vanilla 或 disabled。
  # 默认 placeholder，由外部聊天/TAB/计分板模组读取 Crown 变量显示。
  channels:
    chat: "placeholder"
    tab: "placeholder"
    nametag: "placeholder"
  direct:
    chat:
      template: "{title} {player}: {message}"
    tab:
      template: "{title} {player}"
    nametag:
      template: "{title} {player}"

permissions:
  fallback-op-levels:
    player: 0
    moderator: 2
    admin: 3
    owner: 4

purchase:
  mint-shop-account: "crown:shop"
  operation-timeout-seconds: 15
  maximum-pending-orders-per-player: 1

safety:
  maximum-title-source-length: 512
  maximum-visible-title-length: 64
  maximum-gui-open-count-per-player: 1
```

### 7.1 默认称号语义

玩家显示状态分为：

- `DEFAULT`：使用配置中的默认称号；
- `OWNED`：使用仓库中的某一称号条目；
- `NONE`：不佩戴任何称号。

规则：

1. 新玩家根据 `default-title.equip-for-new-player` 初始化为 `DEFAULT` 或 `NONE`。
2. 默认称号不写入玩家仓库、不收费、不会过期。
3. 默认称号可以在仓库 GUI 中选择佩戴，但不能删除。
4. 玩家可以选择 `NONE`，即使配置启用了默认称号也不显示。
5. `NONE` 会持久保存，重新登录不会自动恢复默认称号。
6. 修改默认称号并重载后，所有处于 `DEFAULT` 状态的玩家立即看到新内容。
7. 当 `default-title.enabled=false` 时，当前处于 `DEFAULT` 的玩家显示为空，但状态仍保留；重新启用后可恢复显示。

### 7.2 存储配置

数据库连接单独放在 `config/crown/storage.yml`。默认使用 SQLite，同时提供可选 MySQL：

```yaml
# Crown 数据库存储配置
# 首次安装建议保持 sqlite；切换后端前必须执行显式迁移命令
config-version: 1

# 存储类型：sqlite 或 mysql
type: "sqlite"

sqlite:
  # SQLite 数据库文件路径
  path: "config/crown/data/crown.db"
  busy-timeout-millis: 5000
  wal: true
  synchronous: "NORMAL"
  snapshot-before-migration: true
  maximum-snapshots: 10

mysql:
  host: "127.0.0.1"
  port: 3306
  database: "crown"
  username: "crown"
  password: "change-me"
  table-prefix: "crown_"
  parameters:
    useSSL: false
    allowPublicKeyRetrieval: false
    serverTimezone: "UTC"
    characterEncoding: "utf8"
  pool:
    minimum-idle: 1
    maximum-size: 10
    connection-timeout-millis: 5000
    validation-timeout-millis: 3000
    idle-timeout-millis: 600000
    maximum-lifetime-millis: 1800000
  require-manual-backup-for-destructive-migration: true

migration:
  auto-compatible-schema: true
  protect-empty-target: true
  verify:
    player-count: true
    owned-title-count: true
    title-coin-total: true
    order-count: true
    card-count: true
    audit-count: true
```

规则：

- 默认 `type: "sqlite"`，不配置 MySQL 也能正常启动；
- 选择 MySQL 时使用 HikariCP 和 MySQL Connector/J；
- MySQL 表名前缀只允许字母、数字和下划线，长度限制为 1～24；
- 数据库密码通过 JDBC 属性传递，不拼接到日志中的连接 URL；
- 只修改 `type` 不会自动搬迁数据；
- 从 SQLite 切换到 MySQL 或从 MySQL 切回 SQLite，必须先执行显式迁移；
- 目标数据库为空而旧数据库存在数据时，安全保护会拒绝直接开放写入；
- 数据库类型、路径、连接信息和连接池配置修改后必须重启；
- `/crown reload` 会校验 `storage.yml`，但不会在运行中替换数据库连接；
- MySQL 首版只支持单个 Crown 服务端实例，不承诺多个服务器并发共享同一数据库。

---

## 8. 称号商品设计

`config/crown/titles.yml` 示例：

```yaml
config-version: 1

titles:
  veteran:
    enabled: true
    category: "normal"
    text: "<gradient:#FFD700:#FF8C00>资深玩家</gradient>"
    prefix: "&7["
    suffix: "&7]"
    icon: "minecraft:nether_star"
    description:
      - "&7服务器资深玩家称号"
      - "&7感谢你的长期支持"

    duration:
      type: "PERMANENT"
      days: 0

    # 付款选项；玩家购买时只能选择其中一种。
    payment-options:
      mint: "1000.00"
      title-coin: "50"

    requirement:
      permission: ""
      deny-if-missing-permission: true

    # 可省略；只有需要销售限制时才添加。
    sale:
      starts-at: null
      ends-at: null
      global-stock: -1
      per-player-limit: 1

    visible: true

  event_winner:
    enabled: true
    category: "event"
    text: "活动冠军"
    prefix: "&#FFD700["
    suffix: "&#FFD700]"
    icon: "minecraft:golden_helmet"
    description:
      - "&e限时 30 天"

    duration:
      type: "LIMITED"
      days: 30

    payment-options:
      title-coin: "50"
      mint: "1000.00"

    requirement:
      permission: "crown.title.event_winner"
      deny-if-missing-permission: true

    sale:
      starts-at: "2026-08-01T00:00:00+08:00"
      ends-at: "2026-08-31T23:59:59+08:00"
      global-stock: 100
      per-player-limit: 1

    visible: true
```

### 8.1 管理员自定义售卖称号

管理员可以通过三种方式创建任意称号商品：

1. 编辑 `titles.yml`；
2. 使用 `/crown title ...` 命令；
3. 使用管理员商城 GUI。

管理员可以设置：

- 商品 ID；
- 分类；
- 称号正文；
- 前缀；
- 后缀；
- 图标；
- Lore；
- 免费、Mint 或称号币价格；
- 全局 Mint 货币 ID（商品使用 `config.yml` 的 `purchase.mint-currency`）；
- 永久或有效天数；
- 开售时间；
- 停售时间；
- 全服库存；
- 单玩家限购；
- 购买权限；
- 是否在商城显示；
- 是否启用。

管理员修改商品时必须原子写回 `titles.yml`。修改只影响未来购买；已购买仓库条目保存购买时快照，不会因商品改名、改价或改有效期而变化。

### 8.2 有效期

- `PERMANENT`：`expires_at` 为 `NULL`；
- `LIMITED`：从成功发放时开始按天计算；
- 有效期按绝对 UTC 时间保存；
- GUI 按服务器时区显示；
- 过期条目仍可在仓库中看到，但不可佩戴；
- 当前佩戴条目过期时自动切换到 `NONE`；
- 删除过期条目不退款；
- 管理员可以把已有条目延期或设为永久。

### 8.3 销售限制

购买确认时和真正发放时都需要检查：

- 商品是否启用；
- 商品是否显示不影响命令购买，但 `enabled=false` 必须拒绝；
- 当前时间是否在销售窗口；
- 全服库存；
- 单玩家购买次数；
- 购买权限；
- 玩家是否存在未完成订单；
- 价格和商品版本是否与确认 GUI 中一致。

商品配置重载后，已打开的旧确认 GUI 不得按旧价格成交。

---

## 9. 玩家自定义称号

玩家自定义称号是商城中的特殊商品，正文由玩家输入，前缀、后缀、价格和有效期由服主控制。

### 9.1 默认有效期

玩家自定义称号默认永久：

```yaml
custom-title:
  duration:
    type: "PERMANENT"
    days: 0
```

服主可以改为限时：

```yaml
custom-title:
  duration:
    type: "LIMITED"
    days: 30
```

重载后只影响后续购买，不修改已有自定义称号条目。

### 9.2 输入流程

1. 玩家执行 `/crown custom` 或点击商城中的“自定义称号”。
2. 检查 `crown.command.custom` 权限和商品是否启用。
3. 关闭当前 GUI。
4. 创建一个有超时时间的输入会话。
5. 提示玩家在聊天栏输入称号正文。
6. 下一条聊天消息由 Crown 截获，不向其他玩家广播。
7. 输入取消关键词时取消会话。
8. 校验通过后打开自定义称号购买确认 GUI。
9. GUI 展示：
   - 原始输入；
   - 实际渲染预览；
   - 统一前缀；
   - 统一后缀；
   - 支付货币；
   - 价格；
   - 有效期。
10. 玩家确认后才执行扣款。
11. 购买成功后创建独立仓库条目。
12. 每次购买可以创建不同自定义称号；玩家可以拥有多个，但同时只能佩戴一个。

### 9.3 输入校验

- 不允许换行；
- 不允许 NUL 和控制字符；
- 不允许超出源码长度；
- 可见字符数量必须位于配置范围；
- 颜色标签本身不计入可见字符长度；
- 不完整或嵌套错误的颜色标签必须拒绝；
- 禁用词在剥离颜色标签后检查；
- 不允许玩家自定义前缀和后缀；
- RGB、渐变和格式码分别受配置控制；
- 使用 RGB/渐变还需要 `crown.shop.custom.color` 权限；
- 购买确认后重新校验输入，防止会话内容被篡改；
- 一个玩家同时只能存在一个自定义输入或购买会话。

### 9.4 管理员直接发放自定义文本

```text
/crown player grant-custom <玩家> <permanent|天数>
```

执行后，管理员在聊天栏输入正文，再通过确认 GUI 发放：

- 不扣玩家货币；
- 使用配置中的自定义称号前后缀；
- 写入管理员审计；
- 可指定永久或有效天数。

---

## 10. 文本与颜色格式

统一文本解析器支持：

```text
&0 ～ &f
&l &m &n &o &k &r
#RRGGBB
&#RRGGBB
<color:#RRGGBB>文字</color>
<gradient:#FF0000:#00FFFF>渐变文字</gradient>
```

示例：

```text
&7[&#FFD700至尊&7]
<color:#55FFFF>冰霜领主</color>
<gradient:#FF0000:#FFFF00>活动冠军</gradient>
```

### 10.1 解析规则

1. 源字符串首先进行长度和控制字符检查。
2. 解析标签为与 Minecraft 无关的 `StyledText` 段列表。
3. Minecraft 版本适配层将 `StyledText` 转换为对应版本的 `Component`。
4. 渐变按照 Unicode code point 插值，不按 UTF-16 单元插值。
5. 格式重置 `&r` 恢复默认样式。
6. 不支持递归 Placeholder 解析。
7. 玩家动态内容不会被当作语言模板或 GUI 模板再次解析。
8. GUI、默认称号、商品称号、玩家自定义称号、前缀和后缀共用同一解析器。

---

## 11. 称号仓库与佩戴

### 11.1 基本模型

玩家可以拥有任意数量的仓库条目，但当前选择最多一个：

```text
DEFAULT
OWNED(entryId)
NONE
```

每次购买、称号卡兑换或管理员发放均生成独立仓库条目。

### 11.2 仓库 GUI 操作

- 左键有效称号：佩戴；
- 左键当前佩戴称号：保持佩戴并提示当前状态；
- 右键普通或自定义仓库条目：打开删除确认 GUI；
- 默认称号：可以左键佩戴，不可删除；
- “不佩戴称号”按钮：切换到 `NONE`；
- 过期称号：不可佩戴，可以删除；
- 翻页按钮：上一页/下一页；
- 关闭按钮：关闭 GUI。

Lore 显示：

- 完整称号预览；
- 商品或仓库条目 ID；
- 来源；
- 获取时间；
- 永久或剩余时间；
- 当前佩戴状态；
- 左键和右键操作提示。

### 11.3 删除规则

- 删除前必须二次确认；
- 删除不退款；
- 默认称号不可删除；
- 删除当前佩戴条目时切换到 `NONE`；
- 删除采用软删除并写审计；
- 管理员可以查看已删除记录，但普通玩家仓库不显示；
- 重复点击删除按钮必须幂等。

---

## 12. 称号币

称号币是 Crown 自己管理的整数货币：

- 名称可配置；
- 符号可配置；
- 只能由管理员发放、扣除或设置；
- 玩家不能付款给其他玩家；
- 玩家不能自行获取或充值；
- 不使用浮点数；
- 余额使用非负 `long`；
- 扣款与称号发放在当前 Crown 数据库后端的同一个事务中完成；
- 每次变动写入不可变流水。

玩家命令：

```text
/crown coin balance
```

管理员命令：

```text
/crown coin give <玩家> <数量>
/crown coin take <玩家> <数量>
/crown coin set <玩家> <数量>
/crown coin look <玩家>
```

管理员发放称号币不走 Mint。

---

## 13. Mint 经济接入

### 13.1 支付账户

玩家账户：

```java
AccountId.player(playerUuid)
```

商城收入账户：

```java
AccountId.of("crown", "shop")
```

默认 reason：

```text
crown:title_purchase
crown:custom_title_purchase
```

### 13.2 Mint 商品金额

- `titles.yml` 中 Mint 价格使用十进制字符串；
- 根据商品 `currency-id` 查询 Mint 货币定义；
- 按货币 scale 和舍入规则转换为最小单位；
- 配置重载时即验证金额，不在玩家点击后才发现价格格式错误；
- 金额必须为正，免费商品使用 `FREE`，不能用零金额伪装付费商品。

### 13.3 购买订单状态

```text
PREPARED
PAYMENT_PENDING
PAYMENT_COMMITTED
GRANTED
FAILED
CANCELLED
```

### 13.4 Mint 购买流程

1. 服务器线程验证商品和玩家。
2. 当前 Crown 数据库后端写入 `PREPARED` 订单及完整商品快照。
3. 为订单生成稳定 Mint transaction ID。
4. 调用 Mint active Provider 的原子 `transfer`：
   - 来源：玩家；
   - 目标：`crown:shop`；
   - 金额：商品价格；
   - reason：Crown 购买原因；
   - metadata：订单、商品和玩家 UUID。
5. 不缓存 Provider；每次操作通过 `Mint.requireEconomy()` 获取。
6. 异步完成后调度回服务器线程。
7. 支付成功后在当前 Crown 数据库后端的事务中：
   - 标记 `PAYMENT_COMMITTED`；
   - 创建仓库条目；
   - 增加库存计数；
   - 标记 `GRANTED`；
   - 写入审计。
8. 向玩家发送成功消息并刷新 GUI。

### 13.5 崩溃恢复

- Crown 启动时扫描未完成订单；
- `PREPARED`/`PAYMENT_PENDING` 使用原 transaction ID 和原请求重试；
- Mint 的幂等事务会返回第一次提交结果，不重复扣款；
- `PAYMENT_COMMITTED` 但尚未 `GRANTED` 的订单继续发放；
- 已 `GRANTED` 的订单不重复创建条目；
- 不允许用新的 transaction ID 重试同一个订单；
- 订单商品快照保证恢复时不受后来改价影响。

### 13.6 失败处理

- 余额不足：订单标记失败，不发称号；
- Provider 不可用：订单保留可恢复状态，向玩家提示稍后重试；
- 请求冲突：安全拒绝并记录严重错误；
- Crown 数据库发放临时失败：保持 `PAYMENT_COMMITTED`，后台及下次启动继续发放；
- 不会在 Mint 已成功扣款后自动改用称号币。

---

## 14. 数据库模型与存储后端

SQLite 和 MySQL 共用同一套逻辑 Schema，建议 Schema 版本为 `1`。存储模块通过 Repository 和 SQL Dialect 抽象屏蔽占位符、自增主键、类型及 UPSERT 语法差异；业务模块不得直接依赖 SQLite 或 MySQL JDBC 类型。

### 14.1 `crown_players`

```text
player_uuid        TEXT PRIMARY KEY
last_known_name    TEXT NOT NULL
selection_type     TEXT NOT NULL  -- DEFAULT / OWNED / NONE
selected_entry_id  TEXT NULL
title_coin_balance INTEGER NOT NULL DEFAULT 0
created_at         INTEGER NOT NULL
updated_at         INTEGER NOT NULL
```

### 14.2 `crown_owned_titles`

```text
entry_id            TEXT PRIMARY KEY
player_uuid         TEXT NOT NULL
definition_id       TEXT NULL
kind                TEXT NOT NULL  -- CATALOG / CUSTOM / CARD / ADMIN_CUSTOM
title_text          TEXT NOT NULL
title_prefix        TEXT NOT NULL
title_suffix        TEXT NOT NULL
source               TEXT NOT NULL
acquired_at          INTEGER NOT NULL
expires_at           INTEGER NULL
purchase_order_id    TEXT NULL UNIQUE
status               TEXT NOT NULL  -- ACTIVE / DELETED
deleted_at           INTEGER NULL
deleted_by           TEXT NULL
```

称号内容保存快照，商品被修改或删除后，玩家已拥有称号仍然可用。

### 14.3 `crown_purchase_orders`

```text
order_id             TEXT PRIMARY KEY
mint_transaction_id  TEXT NULL UNIQUE
player_uuid           TEXT NOT NULL
product_type          TEXT NOT NULL
definition_id         TEXT NULL
payment_type          TEXT NOT NULL
currency_id           TEXT NULL
amount_minor          INTEGER NOT NULL
title_snapshot_json   TEXT NOT NULL
state                 TEXT NOT NULL
entry_id              TEXT NULL UNIQUE
failure_code          TEXT NULL
created_at            INTEGER NOT NULL
updated_at            INTEGER NOT NULL
```

### 14.4 `crown_title_coin_ledger`

```text
ledger_id      INTEGER PRIMARY KEY AUTOINCREMENT
player_uuid    TEXT NOT NULL
delta          INTEGER NOT NULL
balance_before INTEGER NOT NULL
balance_after  INTEGER NOT NULL
actor          TEXT NOT NULL
reason         TEXT NOT NULL
order_id       TEXT NULL
created_at     INTEGER NOT NULL
```

### 14.5 `crown_sale_counters`

```text
definition_id TEXT PRIMARY KEY
sold_count    INTEGER NOT NULL
revision      INTEGER NOT NULL
```

单玩家购买次数从已成功订单聚合或使用独立计数表缓存。

### 14.6 `crown_cards`

```text
card_token     TEXT PRIMARY KEY
definition_id  TEXT NOT NULL
duration_type  TEXT NOT NULL
duration_days  INTEGER NOT NULL
issued_by      TEXT NOT NULL
issued_at      INTEGER NOT NULL
redeemed_by    TEXT NULL
redeemed_at    INTEGER NULL
```

### 14.7 `crown_audit`

```text
audit_id    INTEGER PRIMARY KEY AUTOINCREMENT
actor       TEXT NOT NULL
action      TEXT NOT NULL
player_uuid TEXT NULL
target_id   TEXT NULL
details_json TEXT NOT NULL
created_at  INTEGER NOT NULL
```

### 14.8 SQLite 设置

- 默认存储后端；
- WAL；
- busy timeout；
- 外键；
- `synchronous=NORMAL`；
- 单写线程；
- Schema 版本表；
- 兼容 Schema 升级前按配置创建数据库快照；
- 事务失败完整回滚；
- 关闭服务器时等待有界时间完成队列。

### 14.9 MySQL 设置

- 可选存储后端；
- 使用 HikariCP 有界连接池；
- 使用 MySQL Connector/J；
- 数据库、账户和表前缀由 `storage.yml` 配置；
- 连接和校验超时必须有界；
- 事务隔离级别至少满足单行条件更新、唯一约束和库存预留的一致性要求；
- 所有表使用支持事务和外键的 InnoDB；
- 字符集使用 `utf8mb4`；
- 时间统一保存为 UTC epoch，不依赖数据库会话时区；
- 断线、超时和连接池耗尽时写操作 fail-closed；
- MySQL 破坏性 Schema 升级要求管理员先完成外部备份；
- Crown 不把 MySQL 视为跨服同步方案，首版只支持单个服务端实例。

### 14.10 Schema 与存储迁移

管理员命令：

```text
/crown storage status
/crown storage migrate schema
/crown storage migrate <sqlite|mysql>
```

迁移规则：

1. `schema` 只迁移当前激活后端的 Schema。
2. 兼容升级可以按 `storage.yml` 自动执行；破坏性升级永不自动执行。
3. 后端迁移开始前暂停所有 Crown 数据库写入，但允许读取现有缓存。
4. 迁移目标连接使用 `storage.yml` 中对应后端的设置。
5. 逐表复制玩家、仓库条目、订单、称号币流水、库存、称号卡、审计和 Schema 元数据。
6. 复制结束后按配置验证玩家数、仓库条目数、称号币总量、订单数、卡片数和审计数。
7. 任一复制或校验失败时保留当前激活后端，不自动切换。
8. 迁移成功后仍需管理员修改 `storage.yml` 的 `type` 并重启服务器。
9. SQLite 迁移或 Schema 升级前创建快照；MySQL 外部备份由管理员负责。
10. 仅修改 `type` 不等于迁移；空目标保护检测到旧后端有数据时拒绝开放写入。
11. 迁移期间新购买、称号币变更、佩戴、删除和称号卡兑换返回本地化维护提示。
12. 迁移状态和摘要写入日志与审计，但绝不输出 MySQL 密码。

---

## 15. 称号卡

管理员命令：

```text
/crown card create <称号ID> <permanent|天数> [数量] [玩家]
/crown card redeem
```

规则：

- 称号卡使用随机不可预测 token；
- token 在数据库中只能兑换一次；
- 物品携带 Crown 自定义数据；
- 复制物品不会允许重复兑换；
- 兑换成功生成独立仓库条目；
- 有效期从兑换成功时开始计算；
- 称号卡兑换不扣 Mint 或称号币；
- 无效、已兑换、商品不存在的卡明确拒绝；
- 右键卡片可触发兑换，也保留命令作为兼容入口。

---

## 16. GUI 系统

GUI 基于 SGUI `SimpleGui`。

### 16.1 GUI 清单

- 主菜单；
- 商城；
- 仓库；
- 普通购买确认；
- 自定义称号确认；
- 删除确认；
- 管理员商城；
- 管理员查看玩家仓库。

### 16.2 GUI 文件示例

`config/crown/gui/shop.yml`：

```yaml
config-version: 1

screen:
  type: "GENERIC_9X6"
  title: "<gradient:#FFD700:#FF8C00>Crown 称号商城</gradient>"

content-slots:
  - "0-44"

filler:
  enabled: true
  item: "minecraft:gray_stained_glass_pane"
  name: ""
  hide-tooltip: true

buttons:
  previous:
    slot: 45
    item: "minecraft:arrow"
    name: "&f上一页"
    lore:
      - "&7当前页：{page}/{pages}"

  custom:
    slot: 47
    item: "minecraft:name_tag"
    name: "&#55FFFF自定义称号"
    lore:
      - "&7在聊天栏输入自己的称号"
      - "&e价格：{custom_price}"

  close:
    slot: 49
    item: "minecraft:barrier"
    name: "&c关闭"

  warehouse:
    slot: 51
    item: "minecraft:chest"
    name: "&6我的称号仓库"

  next:
    slot: 53
    item: "minecraft:arrow"
    name: "&f下一页"
    lore:
      - "&7当前页：{page}/{pages}"

title-items:
  available:
    item: "{title_icon}"
    name: "{title_preview}"
    lore:
      - "{title_description}"
      - ""
      - "&e价格：{price} {currency}"
      - "&7有效期：{duration}"
      - "&a点击购买"

  owned:
    item: "{title_icon}"
    name: "{title_preview}"
    glow: true
    lore:
      - "&a已经拥有"

  unavailable:
    item: "minecraft:barrier"
    name: "{title_preview}"
    lore:
      - "&c{unavailable_reason}"
```

### 16.3 可配置项目

- 箱子类型和行数；
- 标题；
- 内容槽位；
- 按钮槽位；
- 物品 ID；
- 名称；
- Lore；
- 数量；
- 发光；
- 隐藏提示；
- 自定义模型数据；
- 填充物；
- 商品状态样式；
- 动态变量；
- 声音；
- 是否关闭后执行操作。

### 16.4 GUI 安全

- 玩家不能取出、放入或移动 GUI 物品；
- 双击、数字键、拖动、Shift 点击均被阻止；
- 每次回调使用稳定 ID，不捕获可变商品对象；
- 点击后重新从当前配置快照和数据库读取；
- 异步购买期间关闭确认按钮，防止重复点击；
- 一个玩家同时只允许一个 Crown GUI；
- 重载时所有打开的 Crown GUI 按新样式重建；
- 对应商品已删除时关闭确认页并提示；
- GUI 配置槽位越界、物品 ID 不存在或按钮冲突时重载失败，继续使用旧配置。

---

## 17. 命令设计

全部命令位于 `/crown`，可选别名 `/title` 由配置决定，默认不开启别名以减少冲突。

### 17.1 玩家命令

```text
/crown
/crown help
/crown shop [分类]
/crown buy <称号ID>
/crown custom
/crown warehouse
/crown open
/crown equip <仓库条目ID|default>
/crown unequip
/crown delete <仓库条目ID>
/crown coin balance
/crown card redeem
```

规则：

- `/crown` 打开主 GUI；
- `/crown open` 和 `/crown warehouse` 等价；
- `/crown buy` 打开确认 GUI，不直接扣款；
- `/crown delete` 打开删除确认 GUI，不直接删除；
- `/crown unequip` 切换为 `NONE`；
- 所有 ID 参数提供 Brigadier 建议；
- 帮助内容根据权限动态显示。

### 17.2 管理员商品命令

```text
/crown title create <ID>
/crown title edit <ID>
/crown title delete <ID>
/crown title list
/crown title enable <ID>
/crown title disable <ID>
/crown title text <ID> <文本>
/crown title prefix <ID> <前缀>
/crown title suffix <ID> <后缀>
/crown title duration <ID> permanent
/crown title duration <ID> <天数>
/crown title payment <ID> free
/crown title payment <ID> mint <currency-id> <价格>
/crown title payment <ID> title_coin <价格>
/crown title permission <ID> <权限节点|none>
/crown title stock <ID> <数量|-1>
/crown title limit <ID> <数量|-1>
/crown title sale-start <ID> <ISO-8601|none>
/crown title sale-end <ID> <ISO-8601|none>
```

`create` 创建安全的禁用草稿，并打开管理 GUI。管理员完成必要字段后再显式启用。

### 17.3 管理员玩家命令

```text
/crown player grant <玩家> <称号ID>
/crown player grant <玩家> <称号ID> permanent
/crown player grant <玩家> <称号ID> <天数>
/crown player grant-custom <玩家> <permanent|天数>
/crown player revoke <玩家> <仓库条目ID>
/crown player list <玩家>
/crown player duration <玩家> <仓库条目ID> permanent
/crown player duration <玩家> <仓库条目ID> <天数>
/crown player selection <玩家> <default|none|仓库条目ID>
/crown view <玩家>
```

### 17.4 管理员称号币命令

```text
/crown coin give <玩家> <数量>
/crown coin take <玩家> <数量>
/crown coin set <玩家> <数量>
/crown coin look <玩家>
```

### 17.5 管理员系统命令

```text
/crown card create <称号ID> <permanent|天数> [数量] [玩家]
/crown storage status
/crown storage migrate schema
/crown storage migrate <sqlite|mysql>
/crown reload
/crown info
/crown audit <订单ID|仓库条目ID>
```

---

## 18. 权限设计

权限节点必须使用 Crown 模组名作为命名空间。

### 18.1 玩家权限

| 权限 | 用途 | 无 LuckPerms 默认等级 |
|---|---|---:|
| `crown.command.open` | 打开仓库 | 0 |
| `crown.command.shop` | 打开商城 | 0 |
| `crown.command.buy` | 购买普通称号 | 0 |
| `crown.command.custom` | 购买自定义称号 | 0 |
| `crown.command.coin` | 查看自己的称号币 | 0 |
| `crown.command.card` | 兑换称号卡 | 0 |
| `crown.shop.custom.color` | 自定义称号使用颜色/渐变 | 0 |
| `crown.title.<id>` | 指定称号购买门槛 | 按商品决定 |

### 18.2 管理员权限

| 权限 | 用途 | 无 LuckPerms 默认等级 |
|---|---|---:|
| `crown.admin.player` | 发放、回收、修改玩家称号 | 3 |
| `crown.admin.title` | 管理称号商品 | 3 |
| `crown.admin.coin` | 管理称号币 | 3 |
| `crown.admin.card` | 创建称号卡 | 3 |
| `crown.admin.view` | 查看其他玩家仓库 | 2 |
| `crown.admin.reload` | 热重载 | 3 |
| `crown.admin.audit` | 审计订单和条目 | 3 |
| `crown.admin.info` | 查看运行状态 | 2 |
| `crown.admin.storage` | 查看存储、执行 Schema/后端迁移 | 4 |

### 18.3 权限策略

1. 未加载 LuckPerms 时使用配置中的原版 OP 等级。
2. LuckPerms 已加载并可正常查询时，其明确 allow/deny 优先。
3. LuckPerms 已加载但 API、用户或查询异常时安全拒绝，不回退 OP 绕过。
4. 控制台可以执行适用的管理员命令。
5. 玩家专属 GUI 和输入命令由控制台执行时返回明确错误。
6. 重载后刷新所有在线玩家的命令树。

---

## 19. Placeholder 变量

Text Placeholder API 是可选集成。未安装时 Crown 其他功能正常运行。

计划提供：

| 变量 | 结果 |
|---|---|
| `%crown:title%` | 完整称号：前缀 + 正文 + 后缀 |
| `%crown:title_text%` | 当前称号正文 |
| `%crown:title_prefix%` | 当前称号前缀 |
| `%crown:title_suffix%` | 当前称号后缀 |
| `%crown:title_id%` | `default`、仓库条目 ID 或空 |
| `%crown:title_definition%` | 商品定义 ID，自定义称号为空 |
| `%crown:title_plain%` | 去除颜色和格式的完整称号 |
| `%crown:title_state%` | `default`、`owned` 或 `none` |
| `%crown:title_expires%` | 永久、本地化剩余时间或空 |
| `%crown:title_coin%` | 格式化称号币余额 |
| `%crown:title_coin_raw%` | 称号币整数余额 |

语义：

- `NONE` 返回空称号组件；
- `DEFAULT` 返回当前配置中的默认称号；
- 已选条目过期时立即返回空并安排状态修复；
- Placeholder handler 只读取内存缓存，不等待当前数据库后端；
- 玩家加入时预热称号和称号币缓存；
- 缓存变更由成功事务主动更新。

---

## 20. 直接显示

`display.direct` 中分别控制：

- 聊天；
- TAB；
- 头顶名称。

### 20.1 聊天

默认关闭。开启时：

- 使用配置模板；
- 保留消息字面内容；
- 不允许玩家消息注入颜色标签；
- 与签名聊天冲突时发送明确启动警告；
- 文档提示与聊天格式模组同时使用可能重复显示。

### 20.2 TAB

默认关闭。开启时修改玩家列表显示名称，并在：

- 玩家加入；
- 玩家更换称号；
- 称号过期；
- 配置重载；
- 权限变化触发刷新时更新。

### 20.3 头顶名称

默认关闭。优先通过 scoreboard team 前后缀实现。

由于队伍系统常被其他模组使用：

- 启动时打印兼容性警告；
- 关闭功能后恢复 Crown 自己创建的队伍；
- 不覆盖非 Crown 队伍；
- 检测冲突时安全跳过该玩家，而不是破坏其他模组状态。

---

## 21. 热重载

命令：

```text
/crown reload
```

权限：

```text
crown.admin.reload
```

### 21.1 重载内容

- `config.yml`；
- `titles.yml`；
- `storage.yml`（只校验，连接设置不在线切换）；
- `lang/*.json`；
- `gui/*.yml`；
- 默认称号；
- 自定义称号价格和规则；
- 权限回退等级；
- 商品价格；
- 商品有效期；
- 销售窗口；
- 显示设置；
- GUI 布局和样式。

### 21.2 事务式重载流程

1. 读取全部文件到新对象。
2. 执行结构和类型校验。
3. 校验所有称号 ID。
4. 校验颜色格式。
5. 校验 Mint 货币和金额。
6. 校验商品时间范围。
7. 校验 GUI 类型、物品、槽位和变量。
8. 校验跨文件引用。
9. 全部成功后原子替换不可变 `RuntimeSnapshot`。
10. 清空语言和文本缓存。
11. 刷新在线玩家显示。
12. 重建打开的 Crown GUI。
13. 重新发送命令树。
14. 输出本地化成功消息和变更摘要。

任意一步失败：

- 不替换旧快照；
- 不关闭旧 GUI；
- 不改变已运行价格；
- 返回具体文件、路径和错误；
- 在日志中输出堆栈；
- 不修改数据库。

### 21.3 不热重载内容

以下设置修改后需要重启：

- 存储后端类型；
- SQLite 文件路径；
- MySQL 地址、端口、数据库、账户、密码、表前缀和 JDBC 参数；
- 数据库执行器与 MySQL 连接池设置；
- Fabric/SGUI/Mint 依赖版本；
- Placeholder 总注册开关；
- Mixin 和直接显示底层挂钩。

---

## 22. 管理员 GUI

管理员 GUI 需要覆盖命令管理的主要能力。

### 22.1 商品管理

- 查看商品列表；
- 创建禁用草稿；
- 启用/停售；
- 修改正文、前缀、后缀；
- 修改图标；
- 修改支付方式；
- 修改价格；
- 修改永久/有效天数；
- 修改库存和限购；
- 修改销售开始/结束时间；
- 删除商品；
- 预览实际效果。

自由文本或数字字段通过聊天输入：

1. 管理员点击字段；
2. GUI 关闭；
3. 管理员在聊天输入新值；
4. Crown 截获消息；
5. 校验后打开确认 GUI；
6. 确认后原子写入 `titles.yml` 并执行内部重载。

### 22.2 玩家仓库管理

- 查看玩家当前状态；
- 查看有效、过期和已删除称号；
- 发放；
- 回收；
- 延期；
- 设为永久；
- 设置当前佩戴；
- 切换为默认或 NONE；
- 查看审计信息。

---

## 23. 审计与日志

必须审计：

- Mint 购买；
- 称号币购买；
- 免费商品领取；
- 管理员发放；
- 管理员回收；
- 玩家删除；
- 称号币 give/take/set；
- 称号卡创建和兑换；
- 商品创建、编辑和删除；
- 管理员修改已有条目有效期；
- 订单恢复；
- 重载成功或失败。

日志不得包含：

- LuckPerms 内部敏感上下文；
- 未经过滤的控制字符；
- 完整异常数据库密码；
- 可伪造的未转义玩家输入。

---

## 24. 并发与一致性

### 24.1 玩家级互斥

每名玩家的以下操作使用同一异步互斥：

- 普通购买；
- 自定义购买；
- 称号币扣款；
- 佩戴；
- 删除；
- 称号卡兑换。

避免同时购买和删除产生状态竞争。

### 24.2 库存

全服限量商品在当前数据库后端的事务中使用 revision 或条件更新：

```sql
UPDATE crown_sale_counters
SET sold_count = sold_count + 1,
    revision = revision + 1
WHERE definition_id = ?
  AND sold_count < ?;
```

更新行数为零表示售罄。仅在称号最终发放时占用库存。

Mint 已付款但此时库存竞争失败不应发生，因为订单创建时需要预留库存。最终实现应采用库存预留字段，并在订单失败/超时后释放。

### 24.3 重复点击

- GUI 确认按钮第一次点击后立即禁用；
- 相同订单重复执行复用相同 transaction ID；
- SQLite 和 MySQL 都对 `purchase_order_id` 和 `entry_id` 建立唯一约束；
- 重复回调返回已有结果。

---

## 25. 启动与关闭生命周期

### 25.1 启动

1. Fabric 初始化；
2. 检查 Mint API major；
3. 创建配置目录；
4. 同步配置、语言和 GUI 默认文件；
5. 构建并验证运行时配置快照；
6. 按 `storage.yml` 打开 SQLite（默认）或 MySQL；
7. 检查并按策略迁移当前后端 Schema；
8. 恢复未完成 Mint 订单；
9. 注册命令；
10. 注册玩家生命周期事件；
11. 注册 SGUI；
12. 如果已安装则注册 LuckPerms；
13. 如果已安装则注册 Placeholder；
14. 注册直接显示挂钩；
15. 输出版本和依赖状态。

### 25.2 关闭

1. 停止接受新购买；
2. 取消聊天输入会话；
3. 关闭 Crown GUI；
4. 等待有界异步任务；
5. 刷新内存状态；
6. 关闭当前数据库后端及其执行器或连接池；
7. 清理 Crown 创建的直接显示状态；
8. 注销监听器。

---

## 26. 测试计划

### 26.1 公共单元测试

- 配置缺失键补全；
- 配置损坏恢复；
- 配置版本过高拒绝；
- 语言同步保留已有文本；
- 语言缺键补入；
- 语言废弃键删除；
- GUI 槽位校验；
- GUI 物品 ID 校验；
- RGB 解析；
- 旧色码解析；
- 渐变 Unicode 插值；
- 可见字符长度；
- 禁用词检查；
- 默认/OWNED/NONE 状态；
- 限时过期；
- 商品销售窗口；
- 单玩家限购；
- 全局库存；
- 称号币溢出和余额不足；
- SQLite 事务回滚；
- MySQL SQL 方言和 Repository 契约；
- SQLite/MySQL 双向迁移、汇总验证和失败回滚；
- 空目标切换保护；
- 删除幂等；
- 称号卡单次兑换；
- Mint 订单状态机；
- 崩溃恢复；
- 配置重载失败保留旧快照。

### 26.x 四版本编译

```bat
gradlew.bat clean check buildAllVersions --no-daemon
```

验证：

- 五个版本独立编译；
- 每个版本只生成一个发布 JAR；
- JAR 内包含 SGUI、SQLite 驱动、HikariCP、MySQL Connector/J 和配置依赖；
- JAR 不重复打包 Mint；
- `fabric.mod.json` 版本范围正确；
- 四个 26.x 版本的字节码均为 Java 25。

### 26.3 真实 Fabric 服务端点烟

`tools/server_smoke.py` 对每个版本：

1. 下载或使用对应 Fabric 服务端；
2. 安装对应 Fabric API 和 Mint JAR；
3. 安装 Crown JAR；
4. 接受 EULA；
5. 启动服务端；
6. 等待 Crown 创建带中文注释的 YML 配置、中文/英文语言文件和默认 SQLite；
7. 验证 Mint Provider 可用；
8. 执行 `/crown info`；
9. 执行 `/crown reload`；
10. 正常 `stop`；
11. 验证无崩溃和数据库非空。

缺少 Mint 的负向测试应验证 Fabric Loader 明确拒绝启动 Crown。

MySQL 不属于默认点烟流程，使用测试专用数据库执行外部集成测试。通过环境变量提供主机、端口、数据库、用户名和密码；测试使用随机表前缀，不创建或删除数据库，并在完成后清理自身测试表。该测试验证 Schema 创建与升级、事务回滚、幂等订单、称号币原子扣款、库存竞争和 SQLite/MySQL 双向迁移。没有真实 MySQL 服务时，不得把“测试源码编译通过”表述为 MySQL 集成测试通过。

### 26.4 手工验收

- 玩家打开商城；
- Mint 购买；
- 称号币购买；
- 自定义聊天输入不广播；
- 自定义购买永久/限时；
- 仓库佩戴；
- 同时只能佩戴一个；
- 选择 NONE；
- 重新登录保持 NONE；
- 佩戴默认称号；
- 删除当前称号后进入 NONE；
- 限时称号过期自动卸下；
- GUI 样式重载即时变化；
- 商品改价后旧确认 GUI不能按旧价购买；
- LuckPerms allow/deny；
- 无 LuckPerms 的 OP 回退；
- Placeholder 正确；
- 直接聊天/TAB/头顶显示开关；
- 四个 26.x 版本的客户端无需安装 Crown 即可使用 GUI。

---

## 27. 验收标准

首个版本完成需满足：

1. 五个目标 Minecraft 版本各自生成可加载 JAR。
2. Mint 缺失时 Crown 无法启动。
3. 玩家能够通过命令和 GUI 完成购买与仓库管理。
4. 管理员能够通过命令和 GUI 创建及管理售卖称号。
5. 玩家可拥有多个称号，但同时只能佩戴一个。
6. 玩家可以选择默认称号、仓库称号或 NONE。
7. NONE 跨重启持久保存。
8. 玩家自定义称号通过聊天输入，消息不广播。
9. 自定义称号默认永久，且可以通过配置改为限时。
10. 普通商品可独立设置永久或有效天数。
11. Mint 和称号币商品均能正确扣款且不会重复扣款。
12. 称号币只能由管理员管理。
13. 支持 RGB、旧式颜色码和渐变。
14. `config/crown/gui/` 中的样式可修改并通过 `/crown reload` 生效。
15. 错误配置重载不会破坏当前运行状态。
16. Placeholder 在安装相应 API 时可用。
17. 直接显示默认关闭，可按位置开启。
18. LuckPerms 和原版 OP 回退符合权限策略。
19. SQLite 和 MySQL 都使用 UUID，玩家改名不丢数据。
20. SQLite 是开箱即用的默认后端，MySQL 可以通过显式迁移安全切换。
21. 购买在崩溃后可以幂等恢复。
22. 所有配置文件均为带简体中文注释的 YML；默认语言仅生成简体中文和英文 JSON。

---

## 28. 实施阶段

### 阶段一：工程与公共基础

- 建立 Gradle 多模块；
- 建立四个 26.x 版本适配；
- 接入 Mint API；
- 接入 SGUI；
- 创建公共领域模型。

### 阶段二：配置与文本

- 配置同步；
- 语言同步；
- GUI 样式；
- 颜色解析；
- 事务式热重载。

### 阶段三：数据与业务

- SQLite/MySQL 公共 Schema、Repository 与 SQL Dialect；
- 存储后端显式迁移和验证；
- 玩家状态；
- 仓库；
- 称号币；
- 商品和库存；
- 审计；
- 称号卡。

### 阶段四：购买

- Mint 订单；
- 称号币事务；
- 免费领取；
- 幂等恢复；
- 限时和限量。

### 阶段五：交互

- 玩家命令；
- 管理员命令；
- 商城 GUI；
- 仓库 GUI；
- 管理 GUI；
- 聊天输入会话。

### 阶段六：显示与集成

- Placeholder；
- LuckPerms；
- 聊天直接显示；
- TAB；
- 头顶名称。

### 阶段七：验证与文档

- 单元测试；
- 四个 26.x 版本构建；
- 服务端点烟；
- 中文 README；
- 配置示例；
- 命令和权限文档；
- 发布 JAR。

---

## 29. 待评审事项

请重点确认：

1. 普通玩家删除仓库称号后不退款，是否接受？
2. 默认称号被禁用时，处于 `DEFAULT` 状态的玩家暂时显示为空；重新启用后恢复，是否接受？
3. 玩家可以购买多个自定义称号，每次生成独立条目，是否保持？
4. 管理员商品修改只影响未来购买，不影响已有条目，是否保持？
5. 直接聊天、TAB、头顶显示默认全部关闭，是否保持？
6. SQLite 作为默认后端、MySQL 作为可选后端，切换必须显式迁移并重启，是否保持？
7. 称号 BUFF 和粒子不进入首个版本，是否保持？
8. `/title` 别名默认关闭，只保留 `/crown`，是否保持？

确认本设计后，以本文档作为首个版本的实现与验收基线。
