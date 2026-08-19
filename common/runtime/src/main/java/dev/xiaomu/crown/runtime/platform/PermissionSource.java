package dev.xiaomu.crown.runtime.platform;

import java.util.Objects;

/**
 * 命令来源的映射中立包装。
 *
 * <p>持有平台原生命令来源对象（不透明 {@code Object}），仅由对应版本源集的
 * {@link PermissionService} 实现向下转型使用。上层逻辑只传递本包装，不感知
 * Minecraft 映射类型。</p>
 */
public final class PermissionSource {
    private final Object nativeSource;

    private PermissionSource(Object nativeSource) {
        this.nativeSource = Objects.requireNonNull(
                nativeSource, "nativeSource");
    }

    public static PermissionSource of(Object nativeSource) {
        return new PermissionSource(nativeSource);
    }

    public Object nativeSource() {
        return nativeSource;
    }
}