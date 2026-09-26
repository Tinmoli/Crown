# Crown

English documentation: [README.md](README.md)

Crown 是 Fabric 服务端称号商城模组。玩家使用箱子菜单购买、自定义和佩戴称号，普通客户端无需安装 Crown。

所有购买使用内置称号币，由管理员通过命令发放。支持限时称号、仓库容量限制、删除退款、颜色与渐变，以及 LuckPerms 权限和 Text Placeholder API 变量。

## 安装

1. 使用 Java 25、Fabric Loader 0.19.3 或更高版本，以及对应 Minecraft 版本的 Fabric API。
2. 将匹配服务端版本的 Crown JAR 放入 `mods`：支持 26.1、26.1.1、26.1.2、26.2，版本包不可混用。
3. 启动服务端，在 `config/crown/` 中生成配置。默认使用 SQLite，无需另装数据库或经济模组。
4. 按需安装服务端 LuckPerms 和 Text Placeholder API；SGUI、颜色解析库及数据库驱动已经包含在 Crown 中。
5. 配置称号显示。默认交给外部模组读取变量；只想使用 Crown 直接显示时，在 `config.yml` 中设置：

```yaml
display:
  channels:
    chat: "vanilla"
    tab: "vanilla"
    nametag: "vanilla"
```

修改后执行 `/crown reload`。使用 TAB 等外部模组时，参阅称号显示与颜色。

## 第一次使用

管理员执行 `/crown give 玩家名 100` 发放称号币。玩家输入 `/crown` 打开主菜单：

- **商城**：选择称号，核对价格和有效期，再确认购买。
- **仓库**：左键佩戴、右键进入删除确认；也可以使用默认称号或不显示称号。
- **自定义称号**：在聊天栏输入正文，预览后确认购买。输入 `取消` 或 `cancel` 退出。

安装 LuckPerms 后，即使是 OP 也需要授予 Crown 管理权限，例如：

```text
/lp user 玩家名 permission set crown.admin.* true
/lp group default permission set crown.shop.custom.color true
```

第一条授予管理员权限，第二条允许普通玩家使用彩色自定义称号。完整说明见权限与 LuckPerms。

## 命令

游戏内带 `/`，控制台去掉 `/`。控制台可以执行币管理和重载，GUI 必须由游戏内玩家打开。

| 命令 | 用途 |
|---|---|
| `/crown` | 主菜单 |
| `/crown help` | 显示有权使用的命令 |
| `/crown shop` | 称号商城 |
| `/crown warehouse` | 称号仓库 |
| `/crown custom` | 自定义称号 |
| `/crown balance` | 自己的称号币余额 |
| `/crown give <玩家> <数量>` | 管理员发币 |
| `/crown take <玩家> <数量>` | 管理员扣币，最多扣到零 |
| `/crown set <玩家> <数量>` | 管理员设置余额 |
| `/crown look <玩家>` | 管理员查看余额 |
| `/crown title` | 商品管理菜单 |
| `/crown reload` | 重载配置、语言和菜单 |

数量为非负整数。玩家之间不能转账。购买、佩戴、删除和商品编辑通过菜单完成。

## 管理商品

输入 `/crown title`，创建商品草稿或选择商品编辑。草稿默认禁用，设置正文、图标、价格和期限后再启用。

菜单会提示聊天输入格式，例如：

```text
text=<gold>活动冠军</gold>
prefix=&7[
suffix=&7]
price=50
duration=limited:30
stock=100
limit=1
```

永久期限使用 `duration=permanent`，无限库存或不限购使用 `stock=unlimited`、`limit=unlimited`。也可直接编辑 `titles.yml`，详见配置说明。

## 仓库上限与删除退款

`config.yml`：

```yaml
purchase:
  maximum-pending-orders-per-player: 1
  maximum-owned-titles-per-player: 20

deletion:
  refund-enabled: true
  refund-percent: 100
  refund-expired: false
```

示例限制每名玩家最多持有 20 个条目。默认上限为 `-1`（不限），`0` 表示禁止新增。普通、自定义及称号卡条目共用名额；已过期但未删除的条目仍占名额，默认称号不占。满仓时拒绝新增，不扣币。

删除退款按该条目**实际购买价格**计算，默认退 100%，可关闭或设置为 0～100 的整数比例，结果向下取整。免费领取和没有购买记录的条目不退款。过期条目默认不退款，可通过 `refund-expired` 开启。

确认页显示预计退款。重复点击不会重复退款；退款超过余额上限时，称号保留。删除不会恢复商品总库存或个人累计限购次数。调低仓库上限不会自动删除现有称号。

## 配置与数据

| 文件 | 内容 |
|---|---|
| `config/crown/config.yml` | 默认称号、自定义、称号币、显示、容量和退款 |
| `config/crown/titles.yml` | 商品 |
| `config/crown/storage.yml` | SQLite / MySQL 连接 |
| `config/crown/lang/*.json` | 提示、帮助和错误文案 |
| `config/crown/gui/*.yml` | 菜单布局和文案 |
| `config/crown/data/crown.db` | 默认 SQLite 玩家数据 |

数据库设置修改后需要重启。备份时先正常停服，再复制整个 `config/crown/`；如果使用 MySQL，还需要备份数据库。不要为了重新生成配置而删除玩家数据库。

配置说明 · 称号显示与颜色 · 权限与 LuckPerms

## 配置解析与显示变量

### 配置解析规则

所有配置文件使用 UTF-8 编码，YAML 缩进必须使用空格。Crown 会自动创建缺失的文件，并为缺失字段补充默认值。配置格式错误时不会替换上一次成功加载的配置。修改商品、语言或 GUI 设置后执行 `/crown reload`；修改数据库连接设置后必须重启服务器。

### 核心配置

| 配置区段 | 常用字段 | 作用 |
|---|---|---|
| `language` | `zh_cn`、`en_us` | 非 GUI 消息语言 |
| `title-coin` | `name`、`symbol`、`maximum-balance` | 称号币显示与余额上限 |
| `custom-title` | `enabled`、`price`、`duration` | 自定义称号购买设置 |
| `custom-title` | `minimum-length`、`maximum-length` | 可见文字长度限制 |
| `custom-title` | `allow-rgb`、`allow-gradient`、`allow-formatting` | 颜色和格式开关 |
| `custom-title` | `forbidden-words` | 非 OP 玩家禁用词 |
| `purchase` | `maximum-owned-titles-per-player` | 仓库容量，`-1` 表示不限 |
| `deletion` | `refund-enabled`、`refund-percent`、`refund-expired` | 删除称号退款设置 |
### 变量参考

| 变量 | 内容 |
|---|---|
| `%crown:title%` | 包含前后缀的完整原生称号组件 |
| `%crown:title_text%` | 带样式的称号正文 |
| `%crown:title_prefix%` / `%crown:title_suffix%` | 称号前缀或后缀 |
| `%crown:title_legacy%` | 转换为原版颜色代码的完整称号 |
| `%crown:title_minimessage%` | MiniMessage 格式的完整称号 |
| `%crown:title_plain%` | 不带颜色和格式的称号 |
| `%crown:title_id%` | 条目 UUID、`default` 或空值 |
| `%crown:title_definition%` | 商品 ID |
| `%crown:title_state%` | `default`、`owned` 或 `none` |
| `%crown:title_expires%` | 剩余时间或永久 |
| `%crown:title_coin%` / `%crown:title_coin_raw%` | 格式化或数字形式的称号币余额 |

使用 `%crown:title%` 的前提是接收模组支持原生文本组件；如果接收模组只接受字符串，请使用 `%crown:title_legacy%`。
## 常见问题

**购买后在哪里佩戴？** 打开仓库，左键点击称号。购买不会自动替换当前佩戴。

**为什么修改商品后，已经买到的称号没有变化？** 仓库保存购买时的文字、前后缀和有效期。商品修改、下架或删除只影响后续购买。

**为什么称号有 UUID？** 每份仓库条目都有独立 UUID，用来区分重复购买的同款称号。仓库同时显示来源、获取时间和到期时间。

**为什么看不到称号？** 默认显示模式是 `placeholder`，需要在外部模组中配置变量；也可以改为 `vanilla`。头顶显示若被其他模组队伍占用，Crown 会避让。

**为什么 OP 没有管理命令？** 安装 LuckPerms 后以 LP 权限为准，按权限文档授权后重新登录刷新命令补全。

**为什么旧菜单没有退款说明？** 已有菜单文案会保留。将删除确认页 lore 改为 `预计退还：{refund} {title_coin_unit}`；仓库导航 lore 可使用 `{owned_count}/{owned_limit}`。也可备份相应 GUI 文件后移走，再重载生成默认菜单，无需删除数据库。

## 许可

[MIT](LICENSE)。
