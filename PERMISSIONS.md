# Crown 权限与 LuckPerms

## 权限如何判断

安装 LuckPerms 后，玩家权限以 LP 的有效结果为准，包含组继承和上下文。明确拒绝的节点不会被 OP 绕过；LP 数据未加载或查询失败时也不会回退 OP。

未设置的普通命令入口默认允许；管理权限、商品额外权限和自定义颜色默认不授予。未安装 LP 时，普通入口与自定义颜色默认允许，管理操作要求原版 OP 等级 3。

控制台不查询玩家 LP 权限，使用原版来源权限。正常服务端控制台可发币、扣币、设置和查看余额、重载配置；GUI 需要游戏内玩家。

## 节点

| 节点 | 用途 |
|---|---|
| `crown.command.open` | 主菜单、帮助、仓库、佩戴、卸下和删除 |
| `crown.command.shop` | 打开商城 |
| `crown.command.buy` | 确认普通或自定义购买 |
| `crown.command.custom` | 自定义称号输入 |
| `crown.command.coin` | 查询自己的余额 |
| `crown.command.card` | 右键兑换 Crown 称号卡 |
| `crown.shop.custom.color` | 自定义颜色及修饰，仍受配置开关限制 |
| `crown.admin.coin` | give、take、set、look |
| `crown.admin.reload` | 重载命令及管理菜单中的重载按钮 |
| `crown.admin.title` | 商品管理菜单及商品编辑 |
| 商品 `requirement.permission` 指定节点 | 特定商品的额外购买条件 |

`crown.command.buy` 是 GUI 权限，不是额外购买命令。帮助只显示拥有权限的管理命令。所有玩家均受仓库容量限制；退款不需要另一个管理权限。

OP 只在**违禁词检查**中按原版 OP 身份豁免，不因此获得其他 LP 权限。

## 授权示例

以下为游戏内命令，控制台执行时去掉 `/`。把玩家名替换成实际名称。

```text
/lp user 玩家名 permission set crown.admin.* true
/lp group default permission set crown.command.* true
/lp group default permission set crown.shop.custom.color true
/lp user 玩家名 permission set crown.title.event_winner true
```

第一条授予所有 Crown 管理权限；第二条允许普通功能；第三条允许彩色自定义；第四条允许购买示例商品“活动冠军”。

只允许发币和查看余额：

```text
/lp user 玩家名 permission set crown.admin.coin true
```

禁止某名玩家购买，但允许查看：

```text
/lp user 玩家名 permission set crown.command.buy false
```

不要给普通玩家组授予 `crown.*`，该通配符也会包含管理员权限。修改权限后可用 `/crown help` 检查；客户端命令补全尚未刷新时重新登录。
