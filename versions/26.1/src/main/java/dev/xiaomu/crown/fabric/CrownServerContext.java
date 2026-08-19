package dev.xiaomu.crown.fabric;

import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.fabric.permission.FabricPermissionService;
import dev.xiaomu.crown.fabric.platform.ServerThreadExecutor;
import dev.xiaomu.crown.fabric.text.CrownMessages;
import dev.xiaomu.crown.fabric.text.FabricTextAdapter;
import dev.xiaomu.crown.runtime.lifecycle.CrownRuntime;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 绑定单个 MinecraftServer 生命周期内的平台服务与公共 Runtime。
 *
 * <p>把映射无关的 Runtime 与需要 Minecraft 类型的适配器（消息渲染、权限、
 * 主线程调度）聚合在一起，供命令、GUI 等交互入口统一取用。</p>
 */
public class CrownServerContext {
    private final MinecraftServer server;
    private final CrownRuntime runtime;
    private final FabricTextAdapter textAdapter;
    private final ServerThreadExecutor mainThread;
    private final FabricPermissionService permissions;

    public CrownServerContext(
            MinecraftServer server,
            CrownRuntime runtime
    ) {
        this.server = server;
        this.runtime = runtime;
        this.textAdapter = new FabricTextAdapter();
        this.mainThread = server == null ? null : new ServerThreadExecutor(server);
        this.permissions = server == null ? null : new FabricPermissionService(server);
    }

    /**
     * 命令注册发生在运行时启动之前。该代理使 Brigadier 节点在注册时可构建，
     * 实际执行时再解析当前服务端上下文。
     */
    public static CrownServerContext deferred(
            Supplier<CrownServerContext> supplier
    ) {
        return new DeferredContext(supplier);
    }

    public MinecraftServer server() {
        return server;
    }

    public CrownRuntime runtime() {
        return runtime;
    }

    public FabricTextAdapter textAdapter() {
        return textAdapter;
    }

    public ServerThreadExecutor mainThread() {
        return mainThread;
    }

    public FabricPermissionService permissions() {
        return permissions;
    }

    /** 每次调用都基于当前快照构建，保证热重载后立即使用新语言。 */
    public CrownMessages messages() {
        return new CrownMessages(
                runtime.snapshot().languages(), textAdapter);
    }

    public CoreSettings core() {
        return runtime.snapshot().core();
    }

    private static final class DeferredContext extends CrownServerContext {
        private final Supplier<CrownServerContext> supplier;

        private DeferredContext(Supplier<CrownServerContext> supplier) {
            super(null, null);
            this.supplier = Objects.requireNonNull(supplier, "supplier");
        }

        private CrownServerContext current() {
            CrownServerContext value = supplier.get();
            if (value == null) {
                throw new IllegalStateException(
                        "Crown runtime is not available yet");
            }
            return value;
        }

        @Override public MinecraftServer server() { return current().server(); }
        @Override public CrownRuntime runtime() { return current().runtime(); }
        @Override public FabricTextAdapter textAdapter() { return current().textAdapter(); }
        @Override public ServerThreadExecutor mainThread() { return current().mainThread(); }
        @Override public FabricPermissionService permissions() { return current().permissions(); }
        @Override public CrownMessages messages() { return current().messages(); }
        @Override public CoreSettings core() { return current().core(); }
    }
}