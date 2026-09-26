# Crown 更新日志

English version: [CHANGELOG.md](CHANGELOG.md)

## Crown v0.1.0

Crown 的第一个版本。这是一个简洁的 Minecraft 称号购买模组，使用称号币购买、佩戴和管理称号。

### 主要功能

- 使用称号币购买称号。
- 管理员可以通过命令发放、扣除和查看称号币。
- 支持通过 GUI 浏览、购买、佩戴和删除称号。
- 支持配置每名玩家最多拥有的称号数量。
- 删除称号时，可以按照实际购买价格比例退还称号币。
- 支持永久称号和限时称号。
- 支持库存限制、个人购买次数限制和购买权限限制。
- 支持创建自定义称号。
- 支持 Minecraft 原版颜色代码、旧版 `§` 颜色代码、十六进制颜色和 MiniMessage 格式。
- 聊天、TAB 和头顶名称显示可以使用同一份称号。
- 支持 PlaceholderAPI 和其他变量模组。
- 提供 LuckPerms 权限节点。
- 支持自定义称号违禁词检查，OP 可以绕过违禁词检查。
- 支持重载配置文件，无需重启服务器。
- 提供英文和中文使用文档。

### 命令

玩家命令：

- `/crown`
- `/crown help`
- `/crown shop`
- `/crown titles`
- `/crown equip <uuid>`
- `/crown unequip`
- `/crown delete <uuid>`

管理员命令：

- `/crown give <player> <amount>`
- `/crown take <player> <amount>`
- `/crown balance <player>`
- `/crown reload`

### 配置说明

首次启动时，Crown 会自动生成配置文件。项目目前仍在开发中，如果配置结构发生变化，可以删除旧配置文件后重新生成。

### 许可证

Crown 使用 [MIT License](LICENSE) 发布。

完整文档：

- [English documentation](README.md)
- [中文文档](README_zh.md)
