# Crown

Crown 是一个 Fabric **服务端**称号商城与仓库模组。玩家通过命令或箱子 GUI 浏览、购买、佩戴和管理称号；普通客户端无需安装 Crown 或 SGUI。

> 详细设计见 `DESIGN.md`。

## 目标版本

| Minecraft | Java | Fabric API | SGUI | Placeholder API |
|---|---:|---|---|---|
| 26.1 | 25 | 0.144.4+26.1 | 2.0.0+26.1 | 3.0.0+26.1 |
| 26.1.1 | 25 | 0.145.4+26.1.1 | 2.0.0+26.1 | 3.0.0+26.1 |
| 26.1.2 | 25 | 0.155.2+26.1.2 | 2.0.0+26.1 | 3.0.0+26.1 |
| 26.2 | 25 | 0.155.2+26.2 | 2.1.0+26.2 | 3.1.0-beta.1+26.2 |

所有版本要求 Fabric Loader `0.19.3+`。每个版本独立构建，禁止跨版本混用 JAR。

26.x 构建脚本采用“版本独立源码 + 公共构建约定”结构：每个 `versions/26.*` 目录都包含完整 Fabric 入口、命令、GUI、Placeholder 和显示代码；`gradle/crown-26-version.gradle` 只集中依赖、Java 和产物规则。新增版本时复制最近版本目录，并按真实 Minecraft API 检查修改该目录代码。

## 依赖

- **强制**：Fabric API、[Mint](https://github.com/Tinmoli/Mint) 经济 API（Mint API major 必须为 1；缺失时 Fabric Loader 直接拒绝加载 Crown）。
- **可选**：LuckPerms（权限）、Text Placeholder API（称号变量）。
- 默认存储 SQLite，可选 MySQL。

发布 Mint API 到本地 Maven：

```bat
E:\XiaoMu\Mint\gradlew.bat :common:api:publishToMavenLocal
```

## 构建

```bat
gradlew.bat buildAllVersions
```

产物收集到 `dist/`：

```text
dist/crown-fabric-26.1-<version>.jar
dist/crown-fabric-26.1.1-<version>.jar
dist/crown-fabric-26.1.2-<version>.jar
dist/crown-fabric-26.2-<version>.jar
```

单独构建某个版本：

```bat
gradlew.bat -PcrownVersions=26.2 :versions:26.2:remapJar
```

只运行与 Minecraft 无关的公共模块测试（不会配置 Loom/Minecraft）：

```bat
gradlew.bat -PcrownVersions=none check
```

运行测试：

```bat
gradlew.bat check
```

## 工程结构

```text
common/domain    与 Minecraft 无关的领域模型、文本解析、校验
common/config    YML 配置、JSON 语言、GUI 样式的安全同步与事务式热重载
common/storage   SQLite/MySQL Schema、Repository、迁移、快照、异步执行器
common/runtime   商城购买状态机、称号币、订单恢复、Mint 网关、只读缓存
versions/26.*        各 Minecraft 版本的完整独立 Fabric 源码和构建目标
```

## 命令

玩家：

```text
/crown                 打开主菜单 GUI
/crown coin balance    查看称号币余额
```

管理员：

```text
/crown info                    运行状态
/crown reload                  事务式热重载
/crown coin give/take/set <玩家> <数量>
/crown coin look <玩家>
```

更多命令、权限节点、GUI 布局和 Placeholder 变量见 `DESIGN.md` §17-§19。

## 配置

首次启动在 `config/crown/` 生成带简体中文注释的默认配置：

```text
config/crown/
├─ config.yml      核心功能、默认称号、称号币、自定义称号、显示、权限
├─ titles.yml      称号商品
├─ storage.yml     SQLite/MySQL 连接与迁移
├─ lang/           zh_cn.json、en_us.json
├─ gui/            八个箱子 GUI 布局
├─ data/           SQLite 数据库
└─ backups/        配置与数据库时间戳备份
```

### 称号显示模式

聊天、TAB 和头顶名称可以分别选择由谁显示，默认全部交给 Placeholder：

```yaml
display:
  channels:
    chat: "placeholder"
    tab: "placeholder"
    nametag: "placeholder"
```

- `placeholder`：Crown 不修改该渠道；Chat/TAB/计分板模组读取 Crown 变量。
- `vanilla`：Crown 通过原版服务端聊天名、PlayerInfo 或 scoreboard team 显示。
- `disabled`：Crown 不主动显示，但 Placeholder 变量仍可被其他系统读取。

安装了聊天格式或 TAB 模组时，建议对应渠道保持 `placeholder`，防止称号重复。
没有其他显示模组并希望 Crown 直接显示时，才将对应渠道改为 `vanilla`。

原版聊天模式只装饰签名聊天的发送者显示名，不修改消息正文、不重新签名，
也不会把玩家消息伪装成系统消息。

### Placeholder API

安装 Text Placeholder API 后可使用：

```text
%crown:title% %crown:title_text% %crown:title_prefix% %crown:title_suffix%
%crown:title_id% %crown:title_definition% %crown:title_plain% %crown:title_state%
%crown:title_expires% %crown:title_coin% %crown:title_coin_raw%
```

在聊天或 TAB 模组中将变量放入对应格式；Crown 默认不会接管这些渠道。只有将
对应渠道设为 `vanilla` 时，Crown 才通过原版服务端机制直接显示。

管理员商品 GUI 的支付设置使用聊天输入：`free`、`title_coin=<正整数>` 或
`mint=<正数价格>`。Mint 货币统一读取 `config.yml` 的
`purchase.mint-currency`。期限/销售设置使用聊天输入：`duration=permanent`、
`duration=limited:<天数>`、`sale-start=<UTC ISO-8601|none>`、
`sale-end=<UTC ISO-8601|none>`、`stock=<正数|unlimited>`、
`limit=<正数|unlimited>`。商品删除必须在二次确认界面确认，已购买的玩家仓库
历史条目不会被删除。

称号商品的最简配置如下：

```yaml
titles:
  welcome:
    text: "欢迎"
    icon: "minecraft:name_tag"
    description:
      - "&7欢迎称号"
    payment-options:
      mint:
        price: "100.00"
      title-coin:
        price: "10"
```

`payment-options` 表示购买界面提供的付款选项，玩家每次只能选择一种。
Mint 的货币 ID 不在每个商品中重复填写，统一使用 `config.yml` 的
`purchase.mint-currency`。省略 `duration` 表示永久，省略 `sale` 表示
没有销售时间、库存和限购限制；只有需要限制时才添加 `sale.starts-at`、
`sale.ends-at`、`sale.global-stock` 或 `sale.per-player-limit`。

GUI 配置中的 `{price}`、`{currency}`、`{duration}` 是 Crown GUI 自身变量，
由对应的 `gui/*.yml` 和运行时数据替换；`%crown:title%` 等是给聊天、TAB
等外部格式使用的 PlaceholderAPI 变量，两者不是同一种占位符。

## 许可

CC0-1.0。