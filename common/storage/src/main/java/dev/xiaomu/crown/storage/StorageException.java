package dev.xiaomu.crown.storage;

/** Crown 持久化操作失败；业务层必须按 fail-closed 处理。 */
public final class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}