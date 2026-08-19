package dev.xiaomu.crown.config.model;

/** 决定某个显示渠道由 Crown 直接处理还是交给外部变量系统。 */
public enum DisplayMode {
    /** Crown 不改原版显示；外部聊天/TAB/记分板模组可读取 Placeholder。 */
    PLACEHOLDER,
    /** Crown 使用该渠道的 direct.template 直接修改原版显示。 */
    VANILLA,
    /** Crown 和 Placeholder 集成均不在该渠道主动显示。 */
    DISABLED;

    public static DisplayMode parse(String value, String path) {
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    path + " must be placeholder, vanilla, or disabled", exception);
        }
    }
}