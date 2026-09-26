# 称号显示、颜色与变量
English display guide: [DISPLAY.md](DISPLAY.md) · 中文入口：[README_zh.md](README_zh.md)

## 选择显示方式

`config.yml` 中，聊天、TAB 列表和头顶名称各自独立配置：

```yaml
display:
  channels:
    chat: "vanilla"
    tab: "placeholder"
    nametag: "placeholder"
  direct:
    chat:
      template: "{title} {player}: {message}"
    tab:
      template: "{title} {player}"
    nametag:
      template: "{title} {player}"
```

| 模式 | 效果 |
|---|---|
| `vanilla` | Crown 直接使用原版机制显示，无需变量模组 |
| `placeholder` | 交给外部聊天、TAB 等模组配置变量 |
| `disabled` | Crown 不主动在该渠道显示，外部变量仍可读取 |

默认三个渠道均为 `placeholder`。只安装变量模组不会自动改变聊天或 TAB，仍须在负责显示的模组中填写变量。

`{title}` 包含完整的称号前缀、正文和后缀。`{player}` 是玩家名。原版聊天模式只装饰发送者名称，消息正文及签名仍由原版处理；聊天模板中的 `{message}` 不用于重写正文。

头顶模式使用玩家队伍前后缀；其他模组已经分配队伍时 Crown 会避让。使用外部 TAB 管理头顶时，推荐把 Crown 的 `tab` 和 `nametag` 都设为 `placeholder`，避免重复显示。

## 颜色输入

普通商品、默认称号和自定义称号支持以下格式。自定义称号需符合配置开关和 LP 颜色权限。

| 格式 | 示例 |
|---|---|
| 传统颜色 | `&a绿色称号` |
| 原版颜色符号 | `§b天蓝称号` |
| RGB | `&#FF8800橙色称号` |
| RGB 其他写法 | `§#FF8800橙色称号`、`#FF8800橙色称号` |
| 长格式 RGB | `&x&F&F&8&8&0&0橙色称号` |
| 原版长格式 RGB | `§x§F§F§8§8§0§0橙色称号` |
| MiniMessage 颜色 | `<red>红色称号</red>` |
| MiniMessage RGB | `<#AA55FF>紫色称号</#AA55FF>` |
| 渐变 | `<gradient:#FF5555:#5555FF>渐变测试</gradient>` |
| 彩虹 | `<rainbow>彩虹测试</rainbow>` |
| 加粗与重置 | `&6&l黄金&r&a新星` |

`§` 可能被原版客户端聊天输入限制，游戏内推荐 `&` 或 MiniMessage；`§` 可写在配置文件中。

- `allow-formatting` 控制传统颜色、修饰及 MiniMessage 命名颜色和修饰。
- `allow-rgb` 控制十六进制颜色。
- `allow-gradient` 控制渐变、彩虹及过渡，同时需要允许 RGB。
- MiniMessage 仅支持称号样式，不提供点击事件、悬停或换行。

彩虹和渐变是静态的，每个字符有自己的颜色。普通原版文本不支持一个字内部多种颜色，也不会自动播放流动动画。

## 外部变量

安装与 Minecraft 版本匹配的 **Text Placeholder API**，并确认接收变量的模组支持它。下列变量需要玩家上下文。

| 变量 | 内容 |
|---|---|
| `%crown:title%` | 完整原版彩色称号：前缀＋正文＋后缀 |
| `%crown:title_text%` | 彩色正文 |
| `%crown:title_prefix%` | 彩色前缀 |
| `%crown:title_suffix%` | 彩色后缀 |
| `%crown:title_plain%` | 完整称号的纯文字 |
| `%crown:title_legacy%` | 带 `&` / `&#RRGGBB` 颜色代码的完整称号 |
| `%crown:title_minimessage%` | MiniMessage 格式的完整称号 |
| `%crown:title_id%` | 当前条目 UUID；默认称号为 `default`，未佩戴为空 |
| `%crown:title_definition%` | 商品 ID；自定义为空，默认称号为 `default` |
| `%crown:title_state%` | `default`、`owned` 或 `none` |
| `%crown:title_expires%` | 剩余时间或永久，未佩戴为空 |
| `%crown:title_coin%` | 按配置格式显示的称号币余额 |
| `%crown:title_coin_raw%` | 纯数字余额 |

原版组件接收方使用 `%crown:title%`。只接收字符串并自己解析颜色的模组，按其支持格式使用 `title_legacy` 或 `title_minimessage`。如果接收方既丢弃样式又不解析颜色代码，Crown 无法单方面让它保留颜色。

## TAB Fabric 6.0.3 示例

该版本的通用变量通道会提取纯文字并丢弃原版组件颜色。使用兼容变量，让 TAB 重新解析颜色：

```yaml
# TAB 的 groups.yml
_DEFAULT_:
  tabprefix: "%crown:title_legacy% "
  tagprefix: "%crown:title_legacy% "
```

如果玩家所属组单独配置了 `tabprefix` 或 `tagprefix`，也要修改该组，不能只改默认组。保存后重载 TAB；Crown 配置修改则执行 `/crown reload`。

LuckPerms 前后缀能显示颜色，是因为 TAB 读取到的颜色代码会由 TAB 解析。Crown 的兼容变量也让 TAB 走颜色代码解析；Crown 自己的 GUI、购买提示、佩戴提示和原版显示仍使用原版彩色文本。

TAB 配置只管理它负责的显示区域。聊天还需要聊天格式模组读取变量，或启用 Crown 的 `chat: "vanilla"`。

## 排查显示问题

1. 确认已经在仓库佩戴，且称号没有过期。
2. 确认商品正文和前后缀实际包含颜色及括号；仅输入“活动冠军”不会自动变成金色。
3. 确认显示渠道选对模式，外部模组使用正确变量。
4. 若 GUI 有颜色而外部模组没有，检查该模组是否丢弃组件样式，按需换兼容变量。
5. 若配置修改没有改变已有称号，这是购买快照的正常行为；用新购买的条目验证新商品样式。
