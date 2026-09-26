package dev.xiaomu.crown.config.model;

import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.domain.catalog.PaymentPolicy;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.text.StyledText;
import dev.xiaomu.crown.domain.text.TextParsePolicy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 经过完整语义校验的 config.yml 快照。 */
public record CoreSettings(
        String language,
        DefaultTitle defaultTitle,
        TitleCoin titleCoin,
        CustomTitle customTitle,
        Display display,
        PermissionFallback permissions,
        Purchase purchase,
        Deletion deletion,
        Safety safety
) {
    public CoreSettings {
        language = requireLanguage(language);
        defaultTitle = Objects.requireNonNull(
                defaultTitle, "defaultTitle");
        titleCoin = Objects.requireNonNull(titleCoin, "titleCoin");
        customTitle = Objects.requireNonNull(customTitle, "customTitle");
        display = Objects.requireNonNull(display, "display");
        permissions = Objects.requireNonNull(permissions, "permissions");
        purchase = Objects.requireNonNull(purchase, "purchase");
        deletion = Objects.requireNonNull(deletion, "deletion");
        safety = Objects.requireNonNull(safety, "safety");
    }

    private static String requireLanguage(String value) {
        Objects.requireNonNull(value, "language");
        if (!value.matches("[a-z0-9_-]{2,32}")) {
            throw new IllegalArgumentException(
                    "Invalid language ID: " + value);
        }
        return value;
    }

    public record DefaultTitle(
            boolean enabled,
            boolean equipForNewPlayer,
            TitleContent content,
            NamespacedId icon
    ) {
        public DefaultTitle {
            content = Objects.requireNonNull(content, "content");
            icon = Objects.requireNonNull(icon, "icon");
        }
    }

    public record TitleCoin(
            String name,
            String symbol,
            String format,
            long maximumBalance
    ) {
        public TitleCoin {
            name = requireText(name, "title coin name", 64, false);
            symbol = requireText(symbol, "title coin symbol", 32, false);
            format = requireText(format, "title coin format", 256, false);
            if (!format.contains("{amount}")) {
                throw new IllegalArgumentException(
                        "Title coin format must contain {amount}");
            }
            if (maximumBalance < 1) {
                throw new IllegalArgumentException(
                        "Maximum title coin balance must be positive");
            }
        }
    }

    public record CustomTitle(
            boolean enabled,
            DurationPolicy duration,
            PaymentPolicy payment,
            String prefixSource,
            String suffixSource,
            StyledText prefix,
            StyledText suffix,
            int minimumLength,
            int maximumLength,
            boolean allowRgb,
            boolean allowGradient,
            boolean allowFormatting,
            Duration inputTimeout,
            List<String> cancelKeywords,
            List<String> forbiddenWords
    ) {
        public CustomTitle {
            duration = Objects.requireNonNull(duration, "duration");
            payment = Objects.requireNonNull(payment, "payment");
            prefixSource = Objects.requireNonNull(
                    prefixSource, "prefixSource");
            suffixSource = Objects.requireNonNull(
                    suffixSource, "suffixSource");
            prefix = Objects.requireNonNull(prefix, "prefix");
            suffix = Objects.requireNonNull(suffix, "suffix");
            if (minimumLength < 1
                    || maximumLength < minimumLength
                    || maximumLength > 256) {
                throw new IllegalArgumentException(
                        "Invalid custom title visible length range");
            }
            inputTimeout = Objects.requireNonNull(
                    inputTimeout, "inputTimeout");
            if (inputTimeout.compareTo(Duration.ofSeconds(5)) < 0
                    || inputTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException(
                        "Custom title input timeout must be 5..600 seconds");
            }
            cancelKeywords = normalizedWords(
                    cancelKeywords, "cancel keyword", false);
            if (cancelKeywords.isEmpty()) {
                throw new IllegalArgumentException(
                        "At least one cancel keyword is required");
            }
            forbiddenWords = normalizedWords(
                    forbiddenWords, "forbidden word", true);
        }

        public TextParsePolicy inputPolicy(int maximumSourceLength) {
            return new TextParsePolicy(
                    allowFormatting,
                    allowRgb,
                    allowGradient,
                    maximumSourceLength,
                    maximumLength);
        }

        /** Compare visible text, ignoring formatting, case, width and invisible format characters. */
        public boolean rejectsTitle(StyledText text, boolean operator) {
            if (operator) return false;
            String visible = normalizeForFilter(text.plainText());
            return forbiddenWords.stream().map(CustomTitle::normalizeForFilter)
                    .filter(word -> !word.isEmpty()).anyMatch(visible::contains);
        }

        private static String normalizeForFilter(String text) {
            String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                    .toLowerCase(java.util.Locale.ROOT);
            var result = new StringBuilder();
            normalized.codePoints().filter(cp -> Character.getType(cp) != Character.FORMAT)
                    .forEach(result::appendCodePoint);
            return result.toString();
        }
    }

    public record Display(
            DisplayMode chatMode,
            DisplayMode tabMode,
            DisplayMode nametagMode,
            DisplayTemplate chat,
            DisplayTemplate tab,
            DisplayTemplate nametag
    ) {
        public Display {
            chatMode = Objects.requireNonNull(chatMode, "chatMode");
            tabMode = Objects.requireNonNull(tabMode, "tabMode");
            nametagMode = Objects.requireNonNull(nametagMode, "nametagMode");
            chat = Objects.requireNonNull(chat, "chat");
            tab = Objects.requireNonNull(tab, "tab");
            nametag = Objects.requireNonNull(nametag, "nametag");
            requireTemplate(chat.template(), "chat", "{player}");
            requireTemplate(chat.template(), "chat", "{title}");
            requireTemplate(tab.template(), "tab", "{player}");
            requireTemplate(tab.template(), "tab", "{title}");
            requireTemplate(nametag.template(), "nametag", "{player}");
            requireTemplate(nametag.template(), "nametag", "{title}");
        }

        private static void requireTemplate(
                String template,
                String name,
                String variable
        ) {
            if (!template.contains(variable)) {
                throw new IllegalArgumentException(
                        name + " template must contain " + variable);
            }
        }
    }

    public record DisplayTemplate(String template) {
        public DisplayTemplate {
            template = requireText(
                    template, "display template", 512, false);
        }
    }

    public record PermissionFallback(
            int player,
            int moderator,
            int admin,
            int owner
    ) {
        public static PermissionFallback defaults() {
            return new PermissionFallback(0, 2, 3, 4);
        }
        public PermissionFallback {
            requireOpLevel(player, "player");
            requireOpLevel(moderator, "moderator");
            requireOpLevel(admin, "admin");
            requireOpLevel(owner, "owner");
            if (player > moderator || moderator > admin || admin > owner) {
                throw new IllegalArgumentException(
                        "Fallback OP levels must be non-decreasing");
            }
        }

        private static void requireOpLevel(int level, String name) {
            if (level < 0 || level > 4) {
                throw new IllegalArgumentException(
                        name + " OP level must be between 0 and 4");
            }
        }

        public Map<String, Integer> asMap() {
            return Map.of(
                    "player", player,
                    "moderator", moderator,
                    "admin", admin,
                    "owner", owner);
        }
    }

    public record Purchase(int maximumPendingOrdersPerPlayer, int maximumOwnedTitlesPerPlayer) {
        public Purchase {
            if (maximumOwnedTitlesPerPlayer < -1) throw new IllegalArgumentException("Invalid warehouse limit");
            if (maximumPendingOrdersPerPlayer < 1 || maximumPendingOrdersPerPlayer > 10) {
                throw new IllegalArgumentException("Pending order limit must be between 1 and 10");
            }
        }
    }
    public record Deletion(boolean refundEnabled, int refundPercent, boolean refundExpired) {
        public Deletion {
            if (refundPercent < 0 || refundPercent > 100)
                throw new IllegalArgumentException("Refund percent must be 0..100");
        }

        public long refund(long paid, boolean expired) {
            if (!refundEnabled || (expired && !refundExpired)) return 0;
            return java.math.BigInteger.valueOf(paid).multiply(
                    java.math.BigInteger.valueOf(refundPercent)).divide(
                    java.math.BigInteger.valueOf(100)).longValueExact();
        }
    }

    public record Safety(
            int maximumTitleSourceLength,
            int maximumVisibleTitleLength,
            int maximumGuiOpenCountPerPlayer
    ) {
        public Safety {
            if (maximumTitleSourceLength < 32
                    || maximumTitleSourceLength > 16_384) {
                throw new IllegalArgumentException(
                        "Maximum title source length must be 32..16384");
            }
            if (maximumVisibleTitleLength < 1
                    || maximumVisibleTitleLength > 256) {
                throw new IllegalArgumentException(
                        "Maximum visible title length must be 1..256");
            }
            if (maximumGuiOpenCountPerPlayer != 1) {
                throw new IllegalArgumentException(
                        "First Crown release requires one GUI per player");
            }
        }

        public TextParsePolicy serverTextPolicy() {
            return new TextParsePolicy(
                    true, true, true,
                    maximumTitleSourceLength,
                    maximumVisibleTitleLength);
        }
    }


    private static List<String> normalizedWords(
            List<String> values,
            String name,
            boolean allowEmpty
    ) {
        Objects.requireNonNull(values, name);
        if (values.size() > 1_000) {
            throw new IllegalArgumentException(
                    "Too many " + name + " values");
        }
        var result = new java.util.LinkedHashSet<String>();
        for (String value : values) {
            String checked = requireText(value, name, 128, false);
            result.add(checked.toLowerCase(java.util.Locale.ROOT));
        }
        if (!allowEmpty && result.isEmpty()) {
            throw new IllegalArgumentException(name + " list is empty");
        }
        return List.copyOf(result);
    }

    private static String requireText(
            String value,
            String name,
            int maximumLength,
            boolean allowBlank
    ) {
        Objects.requireNonNull(value, name);
        if ((!allowBlank && value.isBlank())
                || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " is blank or too long");
        }
        return value;
    }
}
