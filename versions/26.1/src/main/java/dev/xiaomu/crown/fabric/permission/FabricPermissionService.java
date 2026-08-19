package dev.xiaomu.crown.fabric.permission;

import dev.xiaomu.crown.runtime.platform.PermissionService;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 26.x 权限桥。通过反射调用 fabric-permissions-api，避免其发布 JAR 中
 * intermediary 参数类型污染 Mojang-mapped 编译源；API 本身仍作为内嵌依赖。
 */
public final class FabricPermissionService implements PermissionService {
    private static final String PERMISSIONS_CLASS =
            "me.lucko.fabric.api.permissions.v0.Permissions";

    private final MinecraftServer server;
    private final boolean permissionsApiLoaded;
    private final boolean luckPermsLoaded;

    public FabricPermissionService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        FabricLoader loader = FabricLoader.getInstance();
        permissionsApiLoaded = loader.isModLoaded("fabric-permissions-api-v0");
        luckPermsLoaded = loader.isModLoaded("luckperms");
    }

    @Override
    public boolean check(
            UUID playerId,
            String node,
            boolean defaultAllowed
    ) {
        Objects.requireNonNull(playerId, "playerId");
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return defaultAllowed;
        }
        return checkNative(
                player.createCommandSourceStack(), node,
                defaultAllowed ? 0 : 5);
    }

    @Override
    public boolean checkSource(
            PermissionSource source,
            String node,
            int fallbackOpLevel
    ) {
        Object nativeSource = Objects.requireNonNull(
                source, "source").nativeSource();
        if (!(nativeSource instanceof CommandSourceStack commandSource)) {
            return false;
        }
        return checkNative(commandSource, node, fallbackOpLevel);
    }

    private boolean checkNative(
            CommandSourceStack source,
            String node,
            int fallbackOpLevel
    ) {
        Objects.requireNonNull(node, "node");
        if (!permissionsApiLoaded) {
            return hasOpLevel(source, fallbackOpLevel);
        }
        try {
            Class<?> api = Class.forName(PERMISSIONS_CLASS);
            Method check = Arrays.stream(api.getMethods())
                    .filter(method -> method.getName().equals("check"))
                    .filter(method -> method.getParameterCount() == 3)
                    .filter(method -> method.getParameterTypes()[0]
                            .isInstance(source))
                    .filter(method -> method.getParameterTypes()[1]
                            == String.class)
                    .filter(method -> method.getParameterTypes()[2]
                            == int.class)
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException(
                            "Compatible Permissions.check overload missing"));
            return (boolean) check.invoke(
                    null, source, node, fallbackOpLevel);
        } catch (ClassNotFoundException
                 | NoSuchMethodException
                 | IllegalAccessException
                 | InvocationTargetException
                 | LinkageError exception) {
            // LuckPerms 已加载但查询链异常时必须 fail closed，不能借 OP 绕过。
            return !luckPermsLoaded
                    && hasOpLevel(source, fallbackOpLevel);
        }
    }

    private static boolean hasOpLevel(
            CommandSourceStack source,
            int requiredLevel
    ) {
        if (requiredLevel < 0 || requiredLevel > 4) {
            return false;
        }
        // 26.x 原版 OP 判定各版本方法名/类名不稳定，统一走反射：
        // 优先 CommandSourceStack.hasPermission(int)；
        // 不存在则回退 permissions().hasPermission(OpPermission.of(int))。
        Boolean direct = tryDirectHasPermission(source, requiredLevel);
        if (direct != null) {
            return direct;
        }
        Boolean viaPermissionSet =
                tryPermissionSet(source, requiredLevel);
        // 反射都失败时对等级 0 放行，保证基础命令可用。
        return viaPermissionSet != null
                ? viaPermissionSet
                : requiredLevel == 0;
    }

    private static Boolean tryDirectHasPermission(
            CommandSourceStack source,
            int requiredLevel
    ) {
        try {
            Method method = CommandSourceStack.class
                    .getMethod("hasPermission", int.class);
            return (boolean) method.invoke(source, requiredLevel);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Boolean tryPermissionSet(
            CommandSourceStack source,
            int requiredLevel
    ) {
        try {
            Method permissionsMethod =
                    CommandSourceStack.class.getMethod("permissions");
            Object permissionSet = permissionsMethod.invoke(source);
            Class<?> opPerm = Class.forName(
                    "net.minecraft.server.permissions.OpPermission");
            Method of = opPerm.getMethod("of", int.class);
            Object permission = of.invoke(null, requiredLevel);
            Class<?> permissionType = Class.forName(
                    "net.minecraft.server.permissions.Permission");
            Method hasPermission = permissionSet.getClass()
                    .getMethod("hasPermission", permissionType);
            return (boolean) hasPermission.invoke(
                    permissionSet, permission);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }
}
