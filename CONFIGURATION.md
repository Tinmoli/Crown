# Crown 配置说明

所有配置位于服务端的 `config/crown/`。使用 UTF-8 保存，YAML 使用空格缩进；带颜色符号的文本建议用双引号包围。将示例合并到已有的同名段落中，不要重复写两个 `purchase` 或 `custom-title`。

修改后执行 `/crown reload`。数据库连接设置需要重启；错误配置会导致重载失败，当前已加载配置继续使用。缺失配置会自动生成，缺失字段会补充默认值。

## config.yml

| 配置 | 默认值 | 用途 |
|---|---|---|
| `language` | `zh_cn` | 语言文件名称，也提供 `en_us` |
| `default-title.enabled` | `true` | 启用默认称号 |
| `default-title.equip-for-new-player` | `true` | 首次建立玩家数据时选择默认称号 |
| `default-title.text/prefix/suffix` | 萌新及灰色括号 | 默认称号的正文和前后缀 |
| `default-title.icon` | `minecraft:name_tag` | 菜单图标 |
| `title-coin.name` | 称号币 | 币名称 |
| `title-coin.symbol` | ✦ | 币符号 |
| `title-coin.format` | `{amount} {name}` | 币显示格式，支持 `{amount}`、`{name}`、`{symbol}` |
| `title-coin.maximum-balance` | `9223372036854775807` | 余额上限，正整数 |
| `purchase.maximum-pending-orders-per-player` | `1` | 同一玩家未完成订单上限 |
| `purchase.maximum-owned-titles-per-player` | `-1` | 仓库条目上限；-1 不限，0 禁止新增 |
| `deletion.refund-enabled` | `true` | 删除称号时退款 |
| `deletion.refund-percent` | `100` | 实付金额的退款百分比，整数 0～100 |
| `deletion.refund-expired` | `false` | 是否给过期条目退款 |

仓库容量共同统计普通、自定义和称号卡条目，包括过期但未删除的条目。默认称号不占名额。正在处理的购买会预留名额，失败会释放；删除条目后可以重新购买，但商品累计限购仍然有效。调低上限不会清空仓库，OP 也遵守容量限制。

退款以订单实际支付金额为准，不看当前商品价格；例如花 51 币购买，配置退 50%，则退 25 币。没有实际支付记录的条目退 0。删除和退款同时保存；退款无法入账时不会删除称号。删除成功会自动卸下该条目。不会恢复商品库存或累计购买次数。

### 自定义称号

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
  input-timeout-seconds: 60
  cancel-keywords: ["取消", "cancel"]
  forbidden-words: ["服主", "管理员", "管理组", "腐竹", "admin", "administrator", "owner"]
```

玩家只输入正文，前后缀由配置统一添加。价格为非负整数，0 免费。限时使用 `type: "LIMITED"` 和正整数 `days`。

长度按可见 Unicode 字符计算，颜色代码不占正文长度。颜色开关和测试例子见[显示说明](DISPLAY.md)。安装 LP 时，彩色自定义还需要 `crown.shop.custom.color` 权限。

违禁词按可见文字包含匹配，忽略颜色、英文大小写、全角及零宽格式字符；空列表 `[]` 关闭拦截。命中后无法购买并显示警告。**原版 OP 身份只豁免违禁词检查**，不会绕过 LP 权限、颜色规则、容量和价格。

### 显示与输入保护

`display.channels.chat/tab/nametag` 分别支持 `placeholder`、`vanilla`、`disabled`。显示模板和外部变量见[显示说明](DISPLAY.md)。

`safety.maximum-title-source-length` 默认 512，限制包括颜色代码在内的输入长度；`safety.maximum-visible-title-length` 默认 64，限制服务器配置称号的可见长度。一般保持默认值。

## titles.yml

```yaml
config-version: 3
titles:
  champion:
    enabled: true
    visible: true
    text: "<gradient:#FFAA00:#FFFF55>活动冠军</gradient>"
    prefix: "&7["
    suffix: "&7]"
    icon: "minecraft:golden_helmet"
    description:
      - "&7活动专属称号"
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

| 字段 | 说明 |
|---|---|
| 商品 ID（如 `champion`） | 1～64 位小写字母、数字、下划线或连字符 |
| `enabled` | 是否允许购买 |
| `visible` | 是否在普通商城显示 |
| `text/prefix/suffix` | 正文和前后缀，支持颜色 |
| `icon` | 有效 Minecraft 物品 ID |
| `description` | 商城说明文字列表 |
| `price` | 非负整数称号币价格，0 免费 |
| `duration.days` | 从获得时开始计算的有效天数，省略 `duration` 为永久 |
| `requirement.permission` | 额外购买权限，省略 `requirement` 则不额外限制 |
| `sale.global-stock` | 全服累计销售上限，-1 不限制，0 售罄 |
| `sale.per-player-limit` | 每人累计购买次数，-1 不限或正整数 |
| `sale.starts-at/ends-at` | 销售起止时间，可省略，格式如 `2026-10-01T00:00:00Z` |

仓库容量与商品限购不同：删除会释放仓库名额，但不会清除累计购买次数。商品 ID 关联销售记录，不要将旧 ID 重新用于不相关商品。修改商品不会改变已有仓库条目，也不会改变它们的退款基数。

## GUI 菜单

菜单在 `gui/*.yml`。主要文件为 `main`、`shop`、`warehouse`、`purchase-confirm`、`custom-confirm`、`delete-confirm` 和 `admin-shop`。

- `screen.type`：箱子大小，`GENERIC_9X1` 到 `GENERIC_9X6`。
- `screen.title`：菜单标题。
- `content-slots`：列表区槽位，可用 `"10-16"` 表示范围。
- `filler`：空白背景物品。
- `buttons`：按钮槽位、物品、名称和 lore。
- `title-items`：不同状态称号的显示样式。
- `text-values`：GUI 内部的状态和说明文字。

槽位从 0 开始，不应越界或互相重叠。修改显示样式时保留现有按钮标识和动作。

| 位置 | 可用变量示例 |
|---|---|
| 商城 / 购买确认 | `{title_preview}`、`{title_coin_price}`、`{title_coin_unit}`、`{duration}` |
| 仓库条目 | `{entry_id}`、`{title_preview}`、`{source}`、`{acquired_at}`、`{expires}` |
| 仓库导航 | `{page}`、`{pages}`、`{owned_count}`、`{owned_limit}` |
| 删除确认 | `{entry_id}`、`{title_preview}`、`{refund}`、`{title_coin_unit}` |

这些是菜单内部变量，不用于外部 TAB。新增默认 lore 不会覆盖已有自定义 lore；可手动补入变量，或备份移走对应文件后重载重新生成。

## 语言

`language: "zh_cn"` 使用 `lang/zh_cn.json`，`en_us` 使用英文。菜单文案独立位于 `gui/*.yml`，切换语言不会翻译自己写的菜单内容。

提示中的 `%0%` 通常表示 Crown 消息前缀，`%1%`、`%2%` 表示该提示的数据参数；修改文案时保留所需参数。退款提示为 `warehouse.deleted-refund`，满仓提示为 `purchase.warehouse-full`，违禁词警告为 `custom.invalid.forbidden-word`。

## storage.yml 与备份

默认 `type: "sqlite"`，文件在 `config/crown/data/crown.db`。SQLite 路径必须相对于服务端目录，不能包含 `..`。

使用 MySQL 时设为 `type: "mysql"`，填写 `mysql.host/port/database/username/password`；数据库需要提前创建，账号需要 Crown 数据表的读写和建表权限。连接池及 JDBC 参数保留默认值即可，除非数据库环境要求调整。

数据库类型或连接地址变化需要重启；Crown 不负责在两个数据库之间搬移数据。切换到空数据库将读取空的玩家数据。备份 SQLite 时正常停服并复制整个数据目录；MySQL 使用数据库自身备份工具。
