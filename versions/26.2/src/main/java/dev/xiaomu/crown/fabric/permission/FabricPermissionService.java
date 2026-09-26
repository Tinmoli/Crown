package dev.xiaomu.crown.fabric.permission;

import dev.xiaomu.crown.runtime.platform.PermissionService;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;




import java.util.Objects;
import java.util.UUID;

/** 26.x 权限桥：玩家优先使用可选 LuckPerms API，未安装时使用原版权限。 */
public final class FabricPermissionService implements PermissionService {


    private final MinecraftServer server;

    private final boolean luckPermsLoaded;

    public FabricPermissionService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        FabricLoader loader = FabricLoader.getInstance();

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
        if (!source.isPlayer() || !luckPermsLoaded) {
            return hasOpLevel(source, fallbackOpLevel);
        }
        try {
            Object api = Class.forName("net.luckperms.api.LuckPermsProvider")
                    .getMethod("get").invoke(null);
            Object manager = Class.forName("net.luckperms.api.LuckPerms")
                    .getMethod("getUserManager").invoke(api);
            Object user = Class.forName("net.luckperms.api.model.user.UserManager")
                    .getMethod("getUser", UUID.class).invoke(manager, source.getPlayer().getUUID());
            if (user == null) return false;
            Object cache = Class.forName("net.luckperms.api.model.PermissionHolder")
                    .getMethod("getCachedData").invoke(user);
            Object permissions = Class.forName("net.luckperms.api.cacheddata.CachedDataManager")
                    .getMethod("getPermissionData").invoke(cache);
            Object result = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData")
                    .getMethod("checkPermission", String.class).invoke(permissions, node);
            return switch (((Enum<?>) result).name()) {
                case "TRUE" -> true;
                case "FALSE" -> false;
                // 普通玩家入口默认开放；管理员与商品权限必须由 LP 授权。
                case "UNDEFINED" -> fallbackOpLevel == 0 && node.startsWith("crown.command.");
                default -> false;
            };
        } catch (ReflectiveOperationException | LinkageError | ClassCastException exception) {
            // LP 存在但不可用时拒绝授权，不能通过 OP 绕过。
            return false;
        }
    }
    private static boolean hasOpLevel(
            CommandSourceStack source,
            int requiredLevel
    ) {
        return switch (requiredLevel) {
            case 0 -> true;
            case 1 -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR);
            case 2 -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
            case 3 -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN);
            case 4 -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER);
            default -> false;
        };
    }

}
