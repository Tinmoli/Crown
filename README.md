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


## Permissions and LuckPerms

LuckPerms is authoritative when installed. These are the main nodes:

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

### How configuration is parsed

All files use UTF-8. YAML indentation must use spaces. Crown creates missing files and fills missing keys with defaults. An invalid file does not replace the last valid configuration. After changing products, language, or GUI settings, run `/crown reload`; storage connection changes require a server restart.

### Core settings

| Section | Important keys | Description |
|---|---|---|
| `language` | `zh_cn`, `en_us` | Non-GUI message language |
| `title-coin` | `name`, `symbol`, `maximum-balance` | Coin display and balance limit |
| `custom-title` | `enabled`, `price`, `duration` | Custom title purchase settings |
| `custom-title` | `minimum-length`, `maximum-length` | Visible text length limits |
| `custom-title` | `allow-rgb`, `allow-gradient`, `allow-formatting` | Color and formatting switches |
| `custom-title` | `forbidden-words` | Words blocked for non-OP players |
| `purchase` | `maximum-owned-titles-per-player` | Warehouse capacity; `-1` means unlimited |
| `deletion` | `refund-enabled`, `refund-percent`, `refund-expired` | Deletion refund behavior |

Example custom-title settings:

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
  forbidden-words: ["owner", "admin"]
```

### Product configuration

Products are stored in `titles.yml`. Omit `duration` for a permanent title. Set stock or purchase limits to `-1` for unlimited values. Product edits affect future purchases; owned entries keep the text, price basis, and expiry snapshot from purchase time.

```yaml
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

GUI layouts are in `gui/*.yml`; keep existing action names and slots when editing. Non-GUI messages are in `lang/zh_cn.json` and `lang/en_us.json`; keep their keys synchronized. SQLite is the default storage, while `storage.yml` can configure MySQL. Stop the server before backing up `config/crown/`.


## Display and integration reference

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

### Permission reference

| Node | Purpose |
|---|---|
| `crown.command.open` | Main menu, help, warehouse, equip, unequip, and delete |
| `crown.command.shop` | Open the title shop |
| `crown.command.buy` | Confirm GUI purchases |
| `crown.command.custom` | Use the custom-title flow |
| `crown.command.coin` | Check the personal coin balance |
| `crown.command.card` | Redeem a title card |
| `crown.admin.coin` | Use `give`, `take`, `set`, and `look` |
| `crown.admin.reload` | Reload configuration and GUI settings |
| `crown.admin.title` | Open the product administration GUI |
| `crown.shop.custom.color` | Use colors in custom titles |
| `crown.title.<id>` | Product-specific permission from `titles.yml` |

LuckPerms explicit denials are not bypassed by OP. Without LuckPerms, ordinary commands are available by default and administrator commands require OP level 3. Warehouse limits, prices, and color permissions still apply to OP; only forbidden-word checks are bypassed.

Native Minecraft text assigns one color to each character, so gradients and rainbows are static character-based colors rather than animated multi-color glyphs.

## Frequently asked questions

### Where do I equip a purchased title?

Open the warehouse with `/crown warehouse` or from the main GUI, then left-click the title entry. Buying a title does not automatically replace the currently equipped title.

### Why did an owned title keep its old text after I edited the product?

Warehouse entries keep the text, prefix, suffix, price basis, and expiry snapshot from the time of purchase. Product edits, disabling, or deleting a product affect future purchases only.

### Why does every warehouse entry have a UUID?

Each entry has its own UUID so repeated purchases of the same product can be managed separately. The warehouse also shows the entry source, acquisition time, and expiry time.

### Why can I not see my title?

The default display mode exposes placeholders to external mods. Configure the receiving mod with `%crown:title%` or `%crown:title_legacy%`, or set the relevant `display.channels` value to `vanilla`. Another nametag or TAB mod may take ownership of the same display location.

### Why do I not have administrator commands even though I am OP?

When LuckPerms is installed, its effective permissions are authoritative. Grant the required `crown.admin.*` or individual administrator nodes, then reconnect or run `/crown help` to refresh command suggestions. Without LuckPerms, administrator commands require OP level 3.

### Why does the GUI not show a refund message?

The GUI uses its configured lore text. Set the delete-confirmation lore to include `{refund}` and `{title_coin_unit}`, and use `{owned_count}/{owned_limit}` for the warehouse count. GUI files can be backed up and restored before running `/crown reload`; the player database does not need to be deleted.

## Documentation

- Chinese guide: [README_zh.md](README_zh.md)
- Changelog: [CHANGELOG.md](CHANGELOG.md)
- Chinese changelog: [CHANGELOG_zh.md](CHANGELOG_zh.md)

## License

This project is released under the [MIT License](LICENSE). You may use, modify, and redistribute it under the terms of that license.
