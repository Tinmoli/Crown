# Crown Changelog

Chinese version: [CHANGELOG_zh.md](CHANGELOG_zh.md)

## Crown v0.1.0

The first release of Crown, a lightweight Minecraft title shop mod that uses title coins to purchase, equip, and manage titles.

### Features

- Purchase titles with title coins.
- Allow administrators to grant, remove, and check title coins through commands.
- Browse, purchase, equip, and delete titles through the GUI.
- Configure the maximum number of titles a player can own.
- Refund title coins based on the actual purchase price when a title is deleted.
- Support permanent and timed titles.
- Support stock limits, per-player purchase limits, and permission requirements.
- Create custom titles.
- Support Minecraft legacy color codes, the `§` format, hexadecimal colors, and MiniMessage formatting.
- Use one title across chat, TAB, and nametag display integrations.
- Support PlaceholderAPI and other placeholder-based mods.
- Provide LuckPerms permission nodes.
- Check forbidden words in custom titles; OP can bypass the forbidden-word check.
- Reload configuration files without restarting the server.
- Provide English and Chinese user documentation.

### Commands

Player commands:

- `/crown`
- `/crown help`
- `/crown shop`
- `/crown titles`
- `/crown equip <uuid>`
- `/crown unequip`
- `/crown delete <uuid>`

Administrator commands:

- `/crown give <player> <amount>`
- `/crown take <player> <amount>`
- `/crown balance <player>`
- `/crown reload`

### Configuration

Crown generates its configuration files on first startup. The project is still under active development, so old configuration files may be deleted and regenerated when the configuration structure changes.

### License

Crown is released under the [MIT License](LICENSE).

Full documentation:

- [English documentation](README.md)
- [中文文档](README_zh.md)
