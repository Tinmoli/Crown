package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.domain.catalog.DurationPolicy;
import dev.xiaomu.crown.domain.catalog.DurationType;
import dev.xiaomu.crown.domain.catalog.PaymentPolicy;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.catalog.TitleContent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * GUI展示用的价格、有效期与时间格式化工具。
 *
 * <p>只负责把领域模型转换为便于放入 {@code {variable}} 的字面量字符串，
 * 不做颜色解析；颜色由 {@link GuiItems} 统一处理。动态 GUI 文案来自
 * 当前 GUI 布局的 {@code text-values}，不读取语言文件。</p>
 */
public final class GuiFormatting {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    private GuiFormatting() {
    }

    public static String priceText(PaymentPolicy payment,
                                   Map<String, String> textValues) {
        return switch (payment.type()) {
            case FREE -> text(textValues, "free");
            case TITLE_COIN -> Long.toString(payment.titleCoinPrice());
            case MINT -> payment.configuredPrice()
                    .map(price -> price.stripTrailingZeros().toPlainString())
                    .orElse("0");
        };
    }

    public static String currencyText(PaymentPolicy payment,
                                      CoreSettings core) {
        return switch (payment.type()) {
            case FREE -> text(Map.of(), "free");
            case MINT -> core.purchase().mintCurrencyName();
            case TITLE_COIN -> core.titleCoin().name();
        };
    }

    public static String paymentTypeText(PaymentType type,
                                         Map<String, String> textValues) {
        return switch (type) {
            case FREE -> text(textValues, "payment-free");
            case MINT -> text(textValues, "payment-mint");
            case TITLE_COIN -> text(textValues, "payment-title-coin");
        };
    }

    public static String durationText(DurationPolicy duration,
                                      Map<String, String> textValues) {
        if (duration.type() == DurationType.PERMANENT) {
            return text(textValues, "permanent");
        }
        return text(textValues, "days")
                .replace("{days}", Integer.toString(duration.days()));
    }

    public static String previewSource(TitleContent content) {
        return content.prefixSource()
                + content.textSource()
                + content.suffixSource();
    }

    public static String time(Instant instant,
                              Map<String, String> textValues) {
        if (instant == null) {
            return text(textValues, "permanent");
        }
        return TIME.format(instant);
    }

    public static String sourceText(String source,
                                    Map<String, String> textValues) {
        if (source == null || source.isBlank()) {
            return text(textValues, "source-purchase");
        }
        if (source.startsWith("admin:")) {
            return text(textValues, "source-admin");
        }
        if (source.equals("card")) {
            return text(textValues, "source-card");
        }
        return text(textValues, "source-purchase");
    }

    public static String limitText(long value,
                                   Map<String, String> textValues) {
        return value < 0
                ? text(textValues, "unlimited")
                : Long.toString(value);
    }

    public static String statusText(String enumName,
                                    Map<String, String> textValues) {
        return text(textValues, "status-" + enumName.toLowerCase(Locale.ROOT));
    }

    public static String text(Map<String, String> values, String key) {
        return values.getOrDefault(key, key);
    }
}