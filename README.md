# Crown

Crown is a server-side title shop and title warehouse mod for Fabric. Players use chest GUIs to browse, buy, customize, equip, and remove titles. Clients do not need to install Crown.

All purchases use Crown's built-in title coins. Administrators grant coins with commands. Crown also supports timed titles, warehouse limits, deletion refunds, RGB colors, gradients, LuckPerms, and Text Placeholder API.

中文说明：[README_zh.md](README_zh.md)

## Installation

1. Use Java 25, Fabric Loader 0.19.3 or newer, and the matching Fabric API.
2. Put the JAR matching your Minecraft version in the server's `mods` directory. Supported versions are 26.1, 26.1.1, 26.1.2, and 26.2.
3. Start the server once. Crown creates `config/crown/` and uses SQLite by default.
4. LuckPerms and Text Placeholder API are optional integrations. GUI and database libraries are bundled.
5. Configure title display. The default mode provides variables to other mods. To let Crown display titles directly, set the channels to `vanilla` in `config.yml`.

## Commands

Run these commands in game with `/`; remove `/` in the server console.

| Command | Purpose |
|---|---|
| `/crown` | Open the main menu |
| `/crown help` | Show commands available to the sender |
| `/crown shop` | Open the title shop |
| `/crown warehouse` | Open the title warehouse |
| `/crown custom` | Create a custom title |
| `/crown balance` | Check your title coins |
| `/crown give <player> <amount>` | Grant title coins |
| `/crown take <player> <amount>` | Remove title coins, down to zero |
| `/crown set <player> <amount>` | Set a player's balance |
| `/crown look <player>` | View another player's balance |
| `/crown title` | Open the administrator product menu |
| `/crown reload` | Reload language, products, and GUI settings |

Players buy and equip titles through the GUI. There are no `/buy`, `/equip`, `/coin`, or legacy storage commands.

## Product setup

Products are stored in `config/crown/titles.yml`:

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
      - "&7Event title"
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

Omit `duration` for a permanent title. Use `-1` for unlimited stock or purchase limits. A purchased title keeps the text, prefix, suffix, price basis, and expiry snapshot from the time of purchase.

## Warehouse limit and refunds

Add these settings to `config.yml`:

```yaml
purchase:
  maximum-pending-orders-per-player: 1
  maximum-owned-titles-per-player: 20

deletion:
  refund-enabled: true
  refund-percent: 100
  refund-expired: false
```

Normal titles, custom titles, and redeemed title cards share the same warehouse limit. Expired entries still occupy a slot until deleted; the server default title does not. A full warehouse rejects the purchase without charging coins.

Refunds use the amount actually paid for that entry, so later product price changes do not change the refund. Free and administrator-granted entries refund zero. Refunds are calculated with the configured percentage and rounded down. Duplicate deletion cannot issue a second refund. If the refund would exceed the balance limit, the title is kept.

## Colors and display

Crown accepts legacy `&` and `§` codes, RGB forms such as `&#FF8800`, `&x&F&F&8&8&0&0`, and MiniMessage colors, gradients, and rainbows. RGB and advanced formatting are controlled by `custom-title.allow-rgb`, `allow-gradient`, and `allow-formatting`. LuckPerms users also need `crown.shop.custom.color` for colored custom titles.


## Permissions

LuckPerms is authoritative when installed. Useful nodes include:

```text
crown.admin.*
crown.admin.coin
crown.admin.reload
crown.admin.title
crown.command.*
crown.shop.custom.color
```


## Configuration files

| File | Purpose |
|---|---|
| `config/crown/config.yml` | Coins, custom titles, display, limits, and refunds |
| `config/crown/titles.yml` | Shop products |
| `config/crown/storage.yml` | SQLite or MySQL connection |
| `config/crown/lang/*.json` | Non-GUI messages |
| `config/crown/gui/*.yml` | GUI layout and GUI text |
| `config/crown/data/crown.db` | Default SQLite data |


## Complete usage reference

### Display modes

Configure chat, TAB, and nametag independently in `config.yml`:

```yaml
display:
  channels:
    chat: "vanilla"
    tab: "placeholder"
    nametag: "placeholder"
```

`vanilla` uses Minecraft's native display path, `placeholder` exposes variables to another mod, and `disabled` stops active display while keeping variables available. Use `%crown:title%` when the receiving mod preserves native components, or `%crown:title_legacy%` when it expects legacy strings. TAB Fabric 6.0.3 should use `%crown:title_legacy%` in `tabprefix` and `tagprefix`.

### Placeholder variables

| Variable | Value |
|---|---|
| `%crown:title%` | Complete native styled title |
| `%crown:title_text%` | Styled title body |
| `%crown:title_prefix%` / `%crown:title_suffix%` | Styled prefix or suffix |
| `%crown:title_legacy%` | Complete title serialized as legacy color codes |
| `%crown:title_minimessage%` | Complete title serialized as MiniMessage |
| `%crown:title_plain%` | Title without formatting |
| `%crown:title_id%` | Entry UUID, `default`, or empty |
| `%crown:title_definition%` | Product ID |
| `%crown:title_state%` | `default`, `owned`, or `none` |
| `%crown:title_expires%` | Remaining time or permanent |
| `%crown:title_coin%` / `%crown:title_coin_raw%` | Formatted or numeric coin balance |

### Configuration reference

Common `config.yml` options include `language`, `title-coin.maximum-balance`, `custom-title.price`, `custom-title.minimum-length`, `custom-title.maximum-length`, `custom-title.allow-rgb`, `custom-title.allow-gradient`, `custom-title.allow-formatting`, `custom-title.forbidden-words`, `purchase.maximum-owned-titles-per-player`, `deletion.refund-enabled`, `deletion.refund-percent`, and `deletion.refund-expired`. GUI layouts are in `gui/*.yml`; non-GUI messages are in `lang/zh_cn.json` and `lang/en_us.json`. Run `/crown reload` after changing products, language, or GUI settings. Storage connection changes require a restart.

### Permission reference

LuckPerms nodes commonly used are `crown.command.open`, `crown.command.shop`, `crown.command.buy`, `crown.command.custom`, `crown.command.coin`, `crown.command.card`, `crown.admin.coin`, `crown.admin.reload`, `crown.admin.title`, `crown.command.*`, and `crown.shop.custom.color`. LuckPerms is authoritative when installed; explicit denials are not bypassed by OP. Without LuckPerms, ordinary commands are available by default and administrator commands require OP level 3. OP bypasses only forbidden-word checks.

Native Minecraft text assigns one color to each character, so gradients and rainbows are static character-based colors rather than animated multi-color glyphs.

## Documentation

- Chinese guide: [README_zh.md](README_zh.md)

## License

[MIT License](LICENSE).
