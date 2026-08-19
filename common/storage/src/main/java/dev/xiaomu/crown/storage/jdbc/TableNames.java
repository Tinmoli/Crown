package dev.xiaomu.crown.storage.jdbc;

import java.util.Objects;
import java.util.regex.Pattern;

/** 从受限前缀生成全部 SQL 标识符，禁止用户输入直接拼入 SQL。 */
public record TableNames(
        String schemaVersion,
        String players,
        String ownedTitles,
        String purchaseOrders,
        String titleCoinLedger,
        String saleCounters,
        String cards,
        String audit
) {
    private static final Pattern PREFIX =
            Pattern.compile("[A-Za-z0-9_]{1,24}");

    public static TableNames withPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Crown table prefix");
        }
        return new TableNames(
                prefix + "schema_version",
                prefix + "players",
                prefix + "owned_titles",
                prefix + "purchase_orders",
                prefix + "title_coin_ledger",
                prefix + "sale_counters",
                prefix + "cards",
                prefix + "audit"
        );
    }

    public TableNames {
        schemaVersion = requireIdentifier(schemaVersion);
        players = requireIdentifier(players);
        ownedTitles = requireIdentifier(ownedTitles);
        purchaseOrders = requireIdentifier(purchaseOrders);
        titleCoinLedger = requireIdentifier(titleCoinLedger);
        saleCounters = requireIdentifier(saleCounters);
        cards = requireIdentifier(cards);
        audit = requireIdentifier(audit);
    }

    private static String requireIdentifier(String value) {
        Objects.requireNonNull(value, "table name");
        if (!value.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException(
                    "Invalid SQL identifier: " + value);
        }
        return value;
    }
}