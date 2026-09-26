# Crown

English documentation: [README.md](README.md)

Crown 是一个面向 Fabric 服务端的称号商店和称号仓库模组。玩家可以通过箱子式 GUI 浏览、购买、自定义、佩戴和删除称号，客户端无需安装 Crown。

所有购买均使用 Crown 内置的称号币。管理员可以通过命令发放称号币。模组支持限时称号、仓库容量、删除退款、RGB 颜色、渐变、LuckPerms 和 Text Placeholder API。

## 安装

1. 使用 Java 25、Fabric Loader 0.19.3 或更高版本，以及对应 Minecraft 版本的 Fabric API。
2. 将匹配 Minecraft 版本的 Crown JAR 放入服务端 `mods` 文件夹。当前支持 26.1、26.1.1、26.1.2 和 26.2，版本包不能混用。
3. 首次启动服务器。Crown 会创建 `config/crown/`，默认使用 SQLite。
4. LuckPerms 和 Text Placeholder API 是可选集成。GUI、颜色解析库和数据库驱动已内置。
5. 配置称号显示。默认模式会向外部模组提供变量；如果希望 Crown 直接显示称号，请在 `config.yml` 中将对应频道设置为 `vanilla`。

## 命令

游戏内命令带 `/`，控制台执行时去掉 `/`。

| 命令 | 用途 |
|---|---|
| `/crown` | 打开主菜单 |
| `/crown help` | 显示当前发送者可以使用的命令 |
| `/crown shop` | 打开称号商店 |
| `/crown warehouse` | 打开称号仓库 |
| `/crown custom` | 创建自定义称号 |
| `/crown balance` | 查看自己的称号币余额 |
| `/crown give <玩家> <数量>` | 发放称号币 |
| `/crown take <玩家> <数量>` | 扣除称号币，最低扣到 0 |
| `/crown set <玩家> <数量>` | 设置玩家称号币余额 |
| `/crown look <玩家>` | 查看其他玩家余额 |
| `/crown title` | 打开管理员商品管理菜单 |
| `/crown reload` | 重载语言、商品和 GUI 设置 |

玩家通过 GUI 购买和佩戴称号。模组不再注册 `/buy`、`/equip`、`/coin` 或旧版仓库命令。

## 商品设置

商品保存在 `config/crown/titles.yml`：

```yaml
titles:
  champion:
    enabled: true
    visible: true
    text: "<gradient:#FFAA00:#FFFF55>Champion</gradient>"
    prefix: "&7["
    suffix: "&7]"
    icon: "minecraft:golden_helmet"
    description:
      - "&7活动称号"
    price: 50
    duration:
      days: 30
    requirement:
      permission: "crown.title.champion"
      deny-if-missing-permission: true
    sale:
      global-stock: 100
      per-player-limit: 1
```

省略 `duration` 表示永久称号。库存和个人购买次数使用 `-1` 表示不限。商品修改只影响之后的购买；已经拥有的条目会保留购买时的文字、前后缀、价格依据和到期时间快照。

## 仓库上限与删除退款

在 `config.yml` 中配置：

```yaml
purchase:
  maximum-pending-orders-per-player: 1
  maximum-owned-titles-per-player: 20

delete:
  refund-enabled: true
  refund-percent: 100
  refund-expired: false
```

普通称号、自定义称号和称号卡条目共用仓库容量。过期条目在删除前仍占用容量，默认称号不占用容量。仓库已满时会拒绝购买，且不会扣除称号币。

退款按照该条目实际支付的金额计算，因此后续商品改价不会影响退款。免费领取和管理员发放的条目实际支付金额为 0。退款按配置比例计算并向下取整，重复删除不会重复退款。如果退款会超过余额上限，则保留该称号条目。

## 颜色与显示

Crown 支持旧版 `&` 和 `§` 颜色代码、十六进制颜色（例如 `&#FF8800`、`&x&F&F&8&8&0&0`），以及 MiniMessage 颜色、渐变和彩虹效果。`custom-title.allow-rgb`、`allow-gradient` 和 `allow-formatting` 控制这些功能。使用 LuckPerms 时，彩色自定义称号还需要 `crown.shop.custom.color` 权限。

聊天、TAB 和头顶名称可以分别配置：

```yaml
display:
  channels:
    chat: "vanilla"
    tab: "placeholder"
    nametag: "placeholder"
```

`vanilla` 使用 Minecraft 原生显示路径，`placeholder` 向其他模组提供变量，`disabled` 停止主动显示但仍保留变量。接收模组支持原生文本组件时使用 `%crown:title%`；只接受字符串时使用 `%crown:title_legacy%`。TAB Fabric 6.0.3 应在 `tabprefix` 和 `tagprefix` 中使用 `%crown:title_legacy%`。

原版 Minecraft 文本通常按字符分配颜色，因此渐变和彩虹是静态的逐字符颜色，不是动画，也不能让同一个字符内部同时显示多种颜色。

## 权限与 LuckPerms

安装 LuckPerms 后，以 LuckPerms 的有效权限结果为准，包括继承和上下文。OP 不会绕过明确的拒绝节点。

| 权限节点 | 用途 |
|---|---|
| `crown.command.open` | 主菜单、帮助、仓库、佩戴、卸下和删除 |
| `crown.command.shop` | 打开称号商店 |
| `crown.command.buy` | 确认 GUI 购买 |
| `crown.command.custom` | 使用自定义称号流程 |
| `crown.command.coin` | 查看个人称号币余额 |
| `crown.command.card` | 兑换称号卡 |
| `crown.admin.coin` | 使用 `give`、`take`、`set` 和 `look` |
| `crown.admin.reload` | 重载配置和 GUI 设置 |
| `crown.admin.title` | 打开商品管理 GUI |
| `crown.shop.custom.color` | 在自定义称号中使用颜色 |
| `crown.title.<id>` | `titles.yml` 中商品配置的专属权限 |

常用权限组：

```text
crown.admin.*
crown.command.*
crown.shop.custom.color
```

未安装 LuckPerms 时，普通玩家命令默认可用，管理员命令需要 OP 等级 3。仓库容量、价格和颜色权限仍然生效；OP 只绕过自定义称号违禁词检查。

## 配置文件

| 文件 | 用途 |
|---|---|
| `config/crown/config.yml` | 称号币、自定义称号、显示、容量和退款 |
| `config/crown/titles.yml` | 商店商品 |
| `config/crown/storage.yml` | SQLite 或 MySQL 连接 |
| `config/crown/lang/*.json` | 非 GUI 消息 |
| `config/crown/gui/*.yml` | GUI 布局和 GUI 文本 |
| `config/crown/data/crown.db` | 默认 SQLite 数据库 |

### 配置解析规则

所有文件使用 UTF-8 编码，YAML 缩进必须使用空格。Crown 会自动创建缺失文件，并为缺失字段补充默认值。配置格式错误时不会替换上一次成功加载的配置。修改商品、语言或 GUI 设置后执行 `/crown reload`；修改存储连接后必须重启服务器。

### 核心配置

| 配置区段 | 常用字段 | 作用 |
|---|---|---|
| `language` | `zh_cn`、`en_us` | 非 GUI 消息语言 |
| `title-coin` | `name`、`symbol`、`maximum-balance` | 称号币显示和余额上限 |
| `custom-title` | `enabled`、`price`、`duration` | 自定义称号购买设置 |
| `custom-title` | `minimum-length`、`maximum-length` | 可见文字长度限制 |
| `custom-title` | `allow-rgb`、`allow-gradient`、`allow-formatting` | 颜色和格式开关 |
| `custom-title` | `forbidden-words` | 非 OP 玩家禁用的词语 |
| `purchase` | `maximum-owned-titles-per-player` | 仓库容量，`-1` 表示不限 |
| `deletion` | `refund-enabled`、`refund-percent`、`refund-expired` | 删除退款行为 |

自定义称号示例：

```yaml
custom-title:
  enabled: true
  price: 50
  duration:
    type: "PERMANENT"
    days: 0
  prefix: "&7["
  suffix: "&7]"
  minimum-length: 1
  maximum-length: 16
  allow-rgb: true
  allow-gradient: true
  allow-formatting: true
  forbidden-words: ["服主", "管理员", "owner", "admin"]
```

GUI 布局在 `gui/*.yml` 中，修改时请保留已有按钮动作名称和槽位。非 GUI 消息在 `lang/zh_cn.json` 和 `lang/en_us.json` 中，两份语言文件的键名应保持一致。SQLite 是默认存储，也可以在 `storage.yml` 中配置 MySQL。备份 SQLite 前请先正常停止服务器。

## 显示与集成参考

### 变量参考

| 变量 | 内容 |
|---|---|
| `%crown:title%` | 包含前后缀的完整原生称号组件 |
| `%crown:title_text%` | 带样式的称号正文 |
| `%crown:title_prefix%` / `%crown:title_suffix%` | 称号前缀或后缀 |
| `%crown:title_legacy%` | 转换为原版颜色代码的完整称号 |
| `%crown:title_minimessage%` | MiniMessage 格式的完整称号 |
| `%crown:title_plain%` | 不带格式的称号 |
| `%crown:title_id%` | 条目 UUID、`default` 或空值 |
| `%crown:title_definition%` | 商品 ID |
| `%crown:title_state%` | `default`、`owned` 或 `none` |
| `%crown:title_expires%` | 剩余时间或永久 |
| `%crown:title_coin%` / `%crown:title_coin_raw%` | 格式化或数字形式的称号币余额 |

接收模组支持原生文本组件时使用 `%crown:title%`；只接受字符串并自行解析格式时使用 `%crown:title_legacy%` 或 `%crown:title_minimessage%`。

## 常见问题

### 购买后的称号在哪里佩戴？

使用 `/crown warehouse` 或从主菜单打开仓库，然后左键点击称号条目。购买称号不会自动替换当前佩戴的称号。

### 为什么修改商品后，已经购买的称号没有变化？

仓库条目会保存购买时的文字、前缀、后缀、价格依据和到期时间快照。修改、下架或删除商品只影响之后的购买。

### 为什么每个仓库条目都有 UUID？

每个条目都有独立 UUID，用于区分重复购买的同一商品。仓库还会显示条目来源、获取时间和到期时间。

### 为什么看不到称号？

默认显示模式会向外部模组提供变量。请在接收模组中配置 `%crown:title%` 或 `%crown:title_legacy%`，或者将对应的 `display.channels` 改为 `vanilla`。如果其他 TAB 或头顶名称模组占用了相同位置，Crown 会避免重复覆盖。

### 为什么我是 OP 却没有管理员命令？

安装 LuckPerms 后，以 LP 的有效权限为准。授予 `crown.admin.*` 或对应的管理员节点后，重新登录或执行 `/crown help` 刷新命令提示。未安装 LuckPerms 时，管理员命令需要 OP 等级 3。

### 为什么 GUI 没有显示退款信息？

GUI 使用配置文件中的 lore 文本。删除确认页面的 lore 需要包含 `{refund}` 和 `{title_coin_unit}`，仓库数量可以使用 `{owned_count}/{owned_limit}`。可以先备份 GUI 文件，再修改后执行 `/crown reload`，不需要删除玩家数据库。

## 文档

- English documentation: [README.md](README.md)
- English changelog: [CHANGELOG.md](CHANGELOG.md)
- 中文更新日志: [CHANGELOG_zh.md](CHANGELOG_zh.md)

## 许可证

本项目使用 [MIT License](LICENSE) 发布。你可以按照许可证条款使用、修改和重新分发本项目。
