package dev.xiaomu.crown.config.io;

/** 带配置字段路径的校验错误。 */
public final class ConfigValueException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String path;

    public ConfigValueException(String path, String message) {
        super(path + ": " + message);
        this.path = java.util.Objects.requireNonNull(path, "path");
    }

    public ConfigValueException(
            String path,
            String message,
            Throwable cause
    ) {
        super(path + ": " + message, cause);
        this.path = java.util.Objects.requireNonNull(path, "path");
    }

    public String path() {
        return path;
    }
}