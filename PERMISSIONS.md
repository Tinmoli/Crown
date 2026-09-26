# Crown Permissions and LuckPerms

中文权限说明：[PERMISSIONS_zh.md](PERMISSIONS_zh.md) · English entry: [README.md](README.md)

When LuckPerms is installed, its effective result is authoritative, including inheritance and contexts. An explicit denial is not bypassed by OP. If LuckPerms is not installed, ordinary player commands are available by default and administrator commands require OP level 3.

## Nodes

| Node | Purpose |
|---|---|
| `crown.command.open` | Main menu, help, warehouse, equip, unequip, and delete |
| `crown.command.shop` | Open the shop |
| `crown.command.buy` | Confirm GUI purchases |
| `crown.command.custom` | Custom title input |
| `crown.command.coin` | Check personal coin balance |
| `crown.command.card` | Redeem a Crown title card |
| `crown.shop.custom.color` | Use colors and formatting in custom titles |
| `crown.admin.coin` | `give`, `take`, `set`, and `look` |
| `crown.admin.reload` | Reload command and GUI button |
| `crown.admin.title` | Product administration GUI and editing |
| Product `requirement.permission` | Extra permission required for one product |

`crown.command.buy` controls the purchase GUI; it does not register a `/crown buy` command. Warehouse limits apply to every player, including OP. Refunds do not require a separate permission. OP is exempt only from forbidden-word checks.

## Examples

Run these in game with `/`; remove `/` in the console:

```text
/lp user PlayerName permission set crown.admin.* true
/lp group default permission set crown.command.* true
/lp group default permission set crown.shop.custom.color true
/lp user PlayerName permission set crown.title.champion true
```

Do not give ordinary players `crown.*`, because it includes administrator nodes. After changing permissions, run `/crown help` or reconnect to refresh command suggestions.
