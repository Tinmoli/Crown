package dev.xiaomu.crown.storage.backend;

import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.storage.StorageException;
import dev.xiaomu.crown.storage.jdbc.ConnectionFactory;
import dev.xiaomu.crown.storage.jdbc.JdbcDialect;
import dev.xiaomu.crown.storage.jdbc.TableNames;
import dev.xiaomu.crown.storage.repository.JdbcCrownRepository;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个已经建好 Schema、持有明确资源生命周期的 JDBC 后端。 */
public final class StorageBackend implements AutoCloseable {
    private final StorageSettings.Type type;
    private final ConnectionFactory connections;
    private final JdbcDialect dialect;
    private final TableNames tables;
    private final Path sqliteDatabase;
    private final JdbcCrownRepository repository;
    private final AtomicBoolean closed = new AtomicBoolean();

    StorageBackend(
            StorageSettings.Type type,
            ConnectionFactory connections,
            JdbcDialect dialect,
            TableNames tables,
            Path sqliteDatabase
    ) {
        this.type = Objects.requireNonNull(type, "type");
        this.connections = Objects.requireNonNull(
                connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.sqliteDatabase = sqliteDatabase;
        repository = new JdbcCrownRepository(
                connections, dialect, tables, false);
    }

    public StorageSettings.Type type() {
        return type;
    }

    public JdbcCrownRepository repository() {
        requireOpen();
        return repository;
    }

    public ConnectionFactory connections() {
        requireOpen();
        return connections;
    }

    public JdbcDialect dialect() {
        return dialect;
    }

    public TableNames tables() {
        return tables;
    }

    public Optional<Path> sqliteDatabase() {
        return Optional.ofNullable(sqliteDatabase);
    }

    public boolean closed() {
        return closed.get();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new StorageException(
                    "Crown storage backend is closed");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            connections.close();
        }
    }
}