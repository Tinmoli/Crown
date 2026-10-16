# Crown

![Crown](26.1/src/main/resources/assets/crown/logo.svg)

一个使用称号币购买称号的 Fabric 服务端模组，提供 GUI、自定义称号、每日签到，以及可选的权限和变量模组集成。

[English](README.md) · [MIT 许可证](LICENSE)

## 独立的 Minecraft 版本项目

每个版本文件夹都有独立完整的源码、资源、测试、使用文档和 Gradle Wrapper，无需共享项目或其他版本文件夹。main 分支包含所有版本；版本分支只包含对应版本的项目。

| Minecraft | 完整使用与配置文档 | 分支 |
|---|---|---|
| 26.1 | [English](26.1/README.md) · [中文](26.1/README_zh.md) | [26.1](https://github.com/Tinmoli/Crown/tree/26.1) |

## 编译

安装 Java 25，进入需要的版本目录，然后编译：

```powershell
cd 26.1
.\gradlew.bat build
```

Linux/macOS 使用 `sh gradlew build`。模组 JAR 生成在该版本的 `build/libs/` 中，仅用于对应的 Minecraft 版本。依赖、安装、命令、配置和常见问题请阅读相应版本的完整文档。
