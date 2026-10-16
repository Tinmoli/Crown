# Crown

![Crown](26.1.2/src/main/resources/assets/crown/logo.svg)

A Fabric server-side title shop using title coins, with GUIs, custom titles, daily check-in, and optional permission and placeholder integrations.

[中文](README_zh.md) · [MIT License](LICENSE)

## Independent Minecraft projects

Each version folder contains its own complete source, resources, tests, documentation, and Gradle wrapper. No shared project or sibling folder is required. The main branch contains the latest version (26.3); each version branch contains only its matching project.

| Minecraft | Complete usage and configuration guide | Branch |
|---|---|---|
| 26.1.2 | [English](26.1.2/README.md) · [中文](26.1.2/README_zh.md) | [26.1.2](https://github.com/Tinmoli/Crown/tree/26.1.2) |

## Build

Install Java 25, open the desired version folder, and run the build:

```powershell
cd 26.1.2
.\gradlew.bat build
```

On Linux/macOS, use `sh gradlew build`. The JAR is generated in that version's `build/libs/` directory. Only use it with the matching Minecraft version. See the version's guide for dependencies, installation, commands, configuration, and troubleshooting.
