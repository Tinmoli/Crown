package dev.xiaomu.crown.storage.model;

/**
 * 管理员修改已有仓库条目有效期的结果。
 *
 * <p>区分条目不存在、属于其他玩家和已软删除，便于命令明确反馈，
 * 同时避免在失败路径写入审计。</p>
 */
public enum OwnedTitleDurationStatus {
    UPDATED,
    NOT_FOUND,
    NOT_OWNED,
    DELETED
}