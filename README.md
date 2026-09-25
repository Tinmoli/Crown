# Crown

Crown 是 Fabric 服务端称号商城模组。玩家通过箱子 GUI 浏览、购买、佩戴和删除称号，普通客户端无需安装模组。

**所有购买只使用 Crown 内置称号币。管理员通过命令发币，玩家之间不支持转账。无需安装 Mint 或其他经济模组。**

## 安装与版本

- 支持 Minecraft 26.1、26.1.1、26.1.2、26.2，每个版本使用对应 JAR。
- 要求 Java 25、Fabric Loader 0.19.3+、对应版本的 Fabric API。
- SGUI 和数据库驱动随 JAR 打包。
- LuckPerms、Text Placeholder API 为可选集成。
- 默认 SQLite，也保留 MySQL 存储支持。

当前为开发版，配置和数据库按当前结构使用，不提供旧经济版本的升级或价格换算功能。

## 常用命令

玩家：

```text
/crown                         打开主菜单
/crown shop                    打开称号商城
/crown warehouse               打开称号仓库
/crown buy <称号ID>             打开购买确认页
/crown custom                  聊天输入自定义称号，再进入确认页
/crown coin balance            查看称号币余额
```

管理员：

```text
/crown coin give <玩家> <数量>   发放称号币
/crown coin take <玩家> <数量>   扣除称号币
/crown coin set <玩家> <数量>    设置称号币余额
/crown coin look <玩家>         查看指定玩家余额
/crown title                   打开商品管理 GUI
/crown title price <ID> <价格>  设置称号币价格
/crown reload                  重载商品、语言和 GUI 配置
```

发币权限为 `crown.admin.coin`，无权限插件时回退到 OP 等级 3。商品管理使用 `crown.admin.title`。
数量和价格为非负整数，商品 `price: 0` 表示免费领取。

## GUI 使用

1. `/crown` 打开主菜单，选择商城、仓库或自定义称号。
2. 商城左键点击可购买商品，确认页显示称号、称号币价格和有效期。
3. 点击唯一的购买按钮。余额不足时不扣币、不发放；成功后称号进入仓库。
4. 仓库支持佩戴、卸下和删除。删除需要二次确认，不退还称号币。
5. 管理员商品 GUI 支持创建、启停、图标、价格、文本、期限、库存和删除。
6. 点击价格设置后，在聊天中输入 `50` 或 `price=50`；输入 `cancel` 取消。

确认期间商品被改价、修改或删除时，会拒绝旧确认并提示重新打开。重复点击有处理中锁定和订单幂等保护。

## 最简商品配置

首次启动生成 `config/crown/`。

```yaml
# titles.yml
config-version: 3
titles:
  welcome:
    text: "欢迎"
    icon: "minecraft:name_tag"
    description:
      - "&7欢迎称号"
    price: 10
```

省略 `duration` 表示永久；限时称号使用 `duration.days`。按需添加 `sale.global-stock`、
`sale.per-player-limit`、`sale.starts-at`、`sale.ends-at` 和 `requirement.permission`。

自定义称号价格在 `config.yml` 的 `custom-title.price` 中设置。称号币名称、符号和余额上限在 `title-coin` 中设置。
数据库连接设置修改后需要重启；重载会拒绝存储设置变化，继续保留当前配置和连接。

## 称号显示

聊天、TAB、头顶名称分别支持 `placeholder`、`vanilla`、`disabled`：

```yaml
display:
  channels:
    chat: "placeholder"
    tab: "placeholder"
    nametag: "placeholder"
```

`placeholder` 默认交由外部聊天/TAB 模组读取变量；需要 Crown 直接显示时改为 `vanilla`。
聊天只装饰发送者名称，头顶显示会避让其他模组的队伍。

安装 Text Placeholder API 后可使用 `%crown:title%`、`%crown:title_text%`、`%crown:title_prefix%`、
`%crown:title_suffix%`、`%crown:title_id%`、`%crown:title_definition%`、`%crown:title_plain%`、
`%crown:title_state%`、`%crown:title_expires%`、`%crown:title_coin%`、`%crown:title_coin_raw%`。

GUI 文案位于 `gui/*.yml`。`{title_coin_price}`、`{title_coin_unit}` 等是 GUI 内部变量，和 Placeholder API 变量不同。

## 文案配置要求

除 GUI 外，玩家提示、命令帮助、管理员反馈、错误说明和运行日志等可读文案统一放入语言配置文件，
通过 `config.yml` 的 `language` 选择 `lang/zh_cn.json`、`lang/en_us.json` 等语言。
代码只引用语言键和动态参数，不硬编码中文或英文文案，也不把底层英文异常直接显示给玩家。

GUI 文案单独处理，继续使用 `gui/*.yml`，不要求并入上述语言文件。
这项全面清理目前是待办，当前文档更新不代表代码已经全部符合要求。

## 构建与测试

```powershell
# 公共业务测试，不加载 Minecraft
.\gradlew.bat '-PcrownVersions=none' check

# 单版本构建
.\gradlew.bat '-PcrownVersions=26.2' ':versions:26.2:build'

# 四版本构建、测试、源码一致性检查及收集产物
.\gradlew.bat build check verifyVersionSources buildAllVersions
```

产物：`dist/crown-fabric-<Minecraft版本>-0.1.0-SNAPSHOT.jar`。

工程分为 `common/domain`、`common/config`、`common/storage`、`common/runtime` 和四个独立的版本适配目录。
详细边界见 [DESIGN.md](DESIGN.md)，验证记录和 GUI 实机检查清单见 [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md)。

## 许可

CC0-1.0。
