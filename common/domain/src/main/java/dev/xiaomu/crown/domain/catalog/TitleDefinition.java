package dev.xiaomu.crown.domain.catalog;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 经过完整配置校验的称号商品定义。 */
public record TitleDefinition(
        DefinitionId id,
        boolean enabled,
        boolean visible,
        String category,
        TitleContent content,
        NamespacedId icon,
        List<String> description,
        DurationPolicy duration,
        PaymentPolicy payment,
        List<PaymentPolicy> paymentOptions,
        String requiredPermission,
        boolean denyIfMissingPermission,
        SalePolicy sale
) {
    public TitleDefinition {
        id = Objects.requireNonNull(id, "id");
        category = requireText(category, "category", 64);
        content = Objects.requireNonNull(content, "content");
        icon = Objects.requireNonNull(icon, "icon");
        description = List.copyOf(Objects.requireNonNull(
                description, "description"));
        if (description.size() > 32
                || description.stream().anyMatch(line ->
                line == null || line.length() > 512)) {
            throw new IllegalArgumentException(
                    "Title description is invalid");
        }
        duration = Objects.requireNonNull(duration, "duration");
        payment = Objects.requireNonNull(payment, "payment");
        paymentOptions = List.copyOf(Objects.requireNonNull(
                paymentOptions, "paymentOptions"));
        if (paymentOptions.isEmpty()
                || !paymentOptions.contains(payment)) {
            throw new IllegalArgumentException(
                    "Payment options must contain the primary payment");
        }
        requiredPermission = Objects.requireNonNull(
                requiredPermission, "requiredPermission");
        if (!requiredPermission.isEmpty()
                && !requiredPermission.matches(
                "[a-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException(
                    "Invalid title permission");
        }
        sale = Objects.requireNonNull(sale, "sale");
    }

    /** 兼容只有一种支付方式的旧调用方。 */
    public TitleDefinition(
            DefinitionId id,
            boolean enabled,
            boolean visible,
            String category,
            TitleContent content,
            NamespacedId icon,
            List<String> description,
            DurationPolicy duration,
            PaymentPolicy payment,
            String requiredPermission,
            boolean denyIfMissingPermission,
            SalePolicy sale
    ) {
        this(id, enabled, visible, category, content, icon, description,
                duration, payment, List.of(payment), requiredPermission,
                denyIfMissingPermission, sale);
    }

    public Optional<String> permission() {
        return requiredPermission.isEmpty()
                ? Optional.empty()
                : Optional.of(requiredPermission);
    }

    public TitleDefinition withPayment(PaymentPolicy selectedPayment) {
        return new TitleDefinition(
                id, enabled, visible, category, content, icon, description,
                duration, selectedPayment, paymentOptions, requiredPermission,
                denyIfMissingPermission, sale);
    }

    private static String requireText(
            String value,
            String name,
            int maximumLength
    ) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " is blank or too long");
        }
        return value;
    }
}