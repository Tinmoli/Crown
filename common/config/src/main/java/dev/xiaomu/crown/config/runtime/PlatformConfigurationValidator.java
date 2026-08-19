package dev.xiaomu.crown.config.runtime;

/**
 * Minecraft 版本适配层对公共候选快照执行的二阶段校验。
 *
 * <p>实现负责检查物品与声音注册表、版本特定 GUI 能力以及其他无法在
 * 无 Minecraft 依赖的公共模块中验证的内容。</p>
 */
@FunctionalInterface
public interface PlatformConfigurationValidator {
    PlatformConfigurationValidator NO_OP = snapshot -> {
    };

    void validate(RuntimeSnapshot snapshot);
}