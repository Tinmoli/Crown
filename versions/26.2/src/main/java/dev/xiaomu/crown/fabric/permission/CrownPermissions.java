package dev.xiaomu.crown.fabric.permission;

/** DESIGN.md 定义的 Crown 权限节点；命令、GUI 与聊天会话共用。 */
public final class CrownPermissions {
    public static final String COMMAND_OPEN = "crown.command.open";
    public static final String COMMAND_SHOP = "crown.command.shop";
    public static final String COMMAND_BUY = "crown.command.buy";
    public static final String COMMAND_CUSTOM = "crown.command.custom";
    public static final String COMMAND_COIN = "crown.command.coin";
    public static final String COMMAND_CARD = "crown.command.card";
    public static final String CUSTOM_COLOR = "crown.shop.custom.color";

    public static final String ADMIN_PLAYER = "crown.admin.player";
    public static final String ADMIN_TITLE = "crown.admin.title";
    public static final String ADMIN_COIN = "crown.admin.coin";
    public static final String ADMIN_CARD = "crown.admin.card";
    public static final String ADMIN_VIEW = "crown.admin.view";
    public static final String ADMIN_RELOAD = "crown.admin.reload";
    public static final String ADMIN_AUDIT = "crown.admin.audit";
    public static final String ADMIN_INFO = "crown.admin.info";
    public static final String ADMIN_STORAGE = "crown.admin.storage";

    public static final String TITLE_PREFIX = "crown.title.";

    private CrownPermissions() {
    }

    public static String title(String definitionId) {
        return TITLE_PREFIX + definitionId;
    }
}