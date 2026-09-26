# Crown Configuration

中文配置说明：[CONFIGURATION_zh.md](CONFIGURATION_zh.md) · English entry: [README.md](README.md)

All Crown settings are stored in `config/crown/`. Use UTF-8 and spaces for YAML indentation. Run `/crown reload` after changing products, language, or GUI settings. Storage connection changes require a restart. Invalid settings leave the previously loaded configuration active.

## `config.yml`

| Setting | Default | Description |
|---|---:|---|
| `language` | `zh_cn` | Language file (`zh_cn` or `en_us`) |
| `title-coin.name` | `称号币` | Coin display name |
| `title-coin.symbol` | `✦` | Coin symbol |
| `title-coin.maximum-balance` | `9223372036854775807` | Maximum balance per player |
| `custom-title.price` | `50` | Custom title price; `0` is free |
| `custom-title.allow-rgb` | `true` | Allow hexadecimal colors |
| `custom-title.allow-gradient` | `true` | Allow gradients, rainbows, and transitions |
| `custom-title.allow-formatting` | `true` | Allow legacy colors and MiniMessage formatting |
| `purchase.maximum-pending-orders-per-player` | `1` | Pending purchase limit |
| `purchase.maximum-owned-titles-per-player` | `-1` | Warehouse limit; `-1` means unlimited |
| `deletion.refund-enabled` | `true` | Enable deletion refunds |
| `deletion.refund-percent` | `100` | Refund percentage from `0` to `100` |
| `deletion.refund-expired` | `false` | Refund expired entries |

Normal titles, custom titles, and title cards share the warehouse limit. Expired entries continue to occupy a slot until deleted. The server default title does not use a slot. A full warehouse rejects a purchase without charging coins.

Refunds use the actual amount paid by the player. Free and administrator-granted entries refund zero. Refunds are rounded down. A duplicate delete cannot refund twice. If the refund would exceed the configured coin balance limit, the title is kept.

## Custom titles

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
  forbidden-words: ["服主", "管理员", "admin", "owner"]
```

Players enter only the title body. Crown applies the configured prefix and suffix. Length counts visible Unicode characters; formatting codes do not count. OP bypasses only the forbidden-word check.

## `titles.yml`

```yaml
config-version: 3
titles:
  champion:
    enabled: true
    visible: true
    text: "<gradient:#FFAA00:#FFFF55>Champion</gradient>"
    prefix: "&7["
    suffix: "&7]"
    icon: "minecraft:golden_helmet"
    description: ["&7Event title"]
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

Omit `duration` for a permanent title. `sale.global-stock` and `sale.per-player-limit` accept `-1` for unlimited. Product edits affect future purchases; owned entries keep their purchase snapshot and refund basis.

## GUI and language files

GUI layouts are in `gui/*.yml`. Keep existing button action names and slots when editing. Common GUI variables include `{title_preview}`, `{entry_id}`, `{source}`, `{acquired_at}`, `{expires}`, `{owned_count}`, `{owned_limit}`, `{refund}`, and `{title_coin_unit}`.

Non-GUI messages are in `lang/zh_cn.json` and `lang/en_us.json`. Keep both language files' keys in sync. GUI text remains in the GUI YAML files.

## Storage

SQLite is the default. `storage.yml` also supports MySQL. Database connection changes require a restart. Crown does not move data between database backends automatically. Stop the server before backing up SQLite data.
