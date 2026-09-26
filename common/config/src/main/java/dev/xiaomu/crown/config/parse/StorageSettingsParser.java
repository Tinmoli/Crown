package dev.xiaomu.crown.config.parse;

import dev.xiaomu.crown.config.io.YamlValues;
import dev.xiaomu.crown.config.model.StorageSettings;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把 storage.yml 映射转换为不可变连接设置。 */
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
                                "sqlite.synchronous")));

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
                        pool));

        return new StorageSettings(type, sqlite, mysql);
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
