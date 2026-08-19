package dev.xiaomu.crown.config.parse;

import dev.xiaomu.crown.config.io.YamlValues;
import dev.xiaomu.crown.config.model.StorageSettings;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把 storage.yml 映射转换为不可变连接和迁移设置。 */
public final class StorageSettingsParser {
    public StorageSettings parse(Map<String, Object> root) {
        StorageSettings.Type type = ConfigParsing.enumValue(
                StorageSettings.Type.class,
                YamlValues.nonBlankString(root, "type"),
                "type");

        StorageSettings.Sqlite sqlite = ConfigParsing.wrap(
                "sqlite", () -> new StorageSettings.Sqlite(
                        YamlValues.nonBlankString(root, "sqlite.path"),
                        Duration.ofMillis(YamlValues.longValue(
                                root, "sqlite.busy-timeout-millis")),
                        YamlValues.bool(root, "sqlite.wal"),
                        ConfigParsing.enumValue(
                                StorageSettings.SqliteSynchronous.class,
                                YamlValues.nonBlankString(
                                        root, "sqlite.synchronous"),
                                "sqlite.synchronous"),
                        YamlValues.bool(
                                root,
                                "sqlite.snapshot-before-migration"),
                        YamlValues.integer(
                                root, "sqlite.maximum-snapshots")));

        Map<String, Object> rawParameters =
                YamlValues.map(root, "mysql.parameters");
        var parameters = new LinkedHashMap<String, String>();
        rawParameters.forEach((key, value) ->
                parameters.put(key, scalarString(
                        value, "mysql.parameters." + key)));

        StorageSettings.Pool pool = ConfigParsing.wrap(
                "mysql.pool", () -> new StorageSettings.Pool(
                        YamlValues.integer(
                                root, "mysql.pool.minimum-idle"),
                        YamlValues.integer(
                                root, "mysql.pool.maximum-size"),
                        Duration.ofMillis(YamlValues.longValue(
                                root,
                                "mysql.pool.connection-timeout-millis")),
                        Duration.ofMillis(YamlValues.longValue(
                                root,
                                "mysql.pool.validation-timeout-millis")),
                        Duration.ofMillis(YamlValues.longValue(
                                root,
                                "mysql.pool.idle-timeout-millis")),
                        Duration.ofMillis(YamlValues.longValue(
                                root,
                                "mysql.pool.maximum-lifetime-millis"))));

        StorageSettings.Mysql mysql = ConfigParsing.wrap(
                "mysql", () -> new StorageSettings.Mysql(
                        YamlValues.nonBlankString(root, "mysql.host"),
                        YamlValues.integer(root, "mysql.port"),
                        YamlValues.nonBlankString(root, "mysql.database"),
                        YamlValues.nonBlankString(root, "mysql.username"),
                        YamlValues.string(root, "mysql.password"),
                        YamlValues.nonBlankString(
                                root, "mysql.table-prefix"),
                        parameters,
                        pool,
                        YamlValues.bool(root,
                                "mysql.require-manual-backup-for-destructive-migration")));

        StorageSettings.Verification verification =
                new StorageSettings.Verification(
                        YamlValues.bool(
                                root,
                                "migration.verify.player-count"),
                        YamlValues.bool(
                                root,
                                "migration.verify.owned-title-count"),
                        YamlValues.bool(
                                root,
                                "migration.verify.title-coin-total"),
                        YamlValues.bool(
                                root,
                                "migration.verify.order-count"),
                        YamlValues.bool(
                                root,
                                "migration.verify.card-count"),
                        YamlValues.bool(
                                root,
                                "migration.verify.audit-count"));

        return new StorageSettings(
                type,
                sqlite,
                mysql,
                new StorageSettings.Migration(
                        YamlValues.bool(
                                root,
                                "migration.auto-compatible-schema"),
                        YamlValues.bool(
                                root,
                                "migration.protect-empty-target"),
                        verification));
    }

    private static String scalarString(Object value, String path) {
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Number) {
            return value.toString();
        }
        throw new dev.xiaomu.crown.config.io.ConfigValueException(
                path, "JDBC parameter must be a scalar");
    }
}