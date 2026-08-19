package dev.xiaomu.crown.runtime.platform;

import java.util.UUID;

/**
 * 映射中立的权限判定契约。
 *
 * <p>由各版本源集（Mojang / intermediary）提供实现，桥接
 * fabric-permissions-api 与原版 OP 等级回退，从而让命令、GUI 等上层逻辑
 * 不直接依赖 Minecraft 映射相关类型。</p>
 */
public interface PermissionService {
    /**
     * 判定在线玩家是否具备节点。无权限提供方时按 {@code defaultAllowed} 回退。
     */
    boolean check(UUID playerId, String node, boolean defaultAllowed);

    /**
     * 判定命令来源是否具备节点。无权限提供方时回退到指定 OP 等级。
     * 控制台/命令方块等非玩家来源由实现按平台规则处理。
     */
    boolean checkSource(PermissionSource source, String node, int fallbackOpLevel);
}