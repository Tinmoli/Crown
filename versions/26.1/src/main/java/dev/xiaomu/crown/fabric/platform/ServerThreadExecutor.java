package dev.xiaomu.crown.fabric.platform;

import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 把异步存储/支付回调安全地调度回 Minecraft 服务器主线程。
 *
 * <p>存储与 Mint 阶段都在工作线程完成；一旦需要访问玩家、GUI 或发送消息，
 * 必须通过本类切回主线程，符合 DESIGN.md §2.5 不阻塞主线程且回调切回的要求。</p>
 */
public final class ServerThreadExecutor {
    private final MinecraftServer server;

    public ServerThreadExecutor(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    /** 在主线程执行；已在主线程则立即执行。 */
    public void run(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (server.isSameThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }

    /**
     * 当异步阶段完成时，把结果或异常调度回主线程处理。
     * 失败时 {@code onError} 接收异常，两个回调都在主线程执行。
     */
    public <T> void whenComplete(
            CompletionStage<T> stage,
            Consumer<? super T> onSuccess,
            Consumer<? super Throwable> onError
    ) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onError, "onError");
        stage.whenComplete((value, failure) -> run(() -> {
            if (failure != null) {
                onError.accept(failure);
            } else {
                onSuccess.accept(value);
            }
        }));
    }
}