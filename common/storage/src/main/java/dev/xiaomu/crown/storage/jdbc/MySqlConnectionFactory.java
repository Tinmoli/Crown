package dev.xiaomu.crown.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** HikariCP MySQL 工厂；凭据通过属性设置，不写入 JDBC URL。 */
public final class MySqlConnectionFactory implements ConnectionFactory {
    private static final Pattern PARAMETER =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");

    private final HikariDataSource dataSource;

    public MySqlConnectionFactory(
            String host,
            int port,
            String database,
            String username,
            String password,
            Map<String, String> parameters,
            int minimumIdle,
            int maximumSize,
            long connectionTimeoutMillis,
            long validationTimeoutMillis,
            long idleTimeoutMillis,
            long maximumLifetimeMillis
    ) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(parameters, "parameters");
        if (host.isBlank() || database.isBlank()
                || port < 1 || port > 65_535
                || minimumIdle < 0 || maximumSize < 1
                || minimumIdle > maximumSize) {
            throw new IllegalArgumentException(
                    "Invalid MySQL pool configuration");
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("Crown-MySQL");
        config.setJdbcUrl(
                "jdbc:mysql://" + host + ':' + port + '/' + database);
        config.setUsername(username);
        config.setPassword(password);
        config.setMinimumIdle(minimumIdle);
        config.setMaximumPoolSize(maximumSize);
        config.setConnectionTimeout(
                Math.max(250L, connectionTimeoutMillis));
        config.setValidationTimeout(
                Math.max(250L, validationTimeoutMillis));
        config.setIdleTimeout(Math.max(10_000L, idleTimeoutMillis));
        config.setMaxLifetime(
                Math.max(30_000L, maximumLifetimeMillis));
        config.addDataSourceProperty("characterEncoding", "utf8");
        config.addDataSourceProperty("serverTimezone", "UTC");
        parameters.forEach((key, value) -> {
            if (!PARAMETER.matcher(key).matches()
                    || value == null || value.length() > 512) {
                throw new IllegalArgumentException(
                        "Invalid MySQL data source parameter");
            }
            config.addDataSourceProperty(key, value);
        });
        dataSource = new HikariDataSource(config);
    }

    @Override
    public Connection open() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}