package dev.xiaomu.crown.fabric.text;

import dev.xiaomu.crown.config.lang.LanguageCatalog;
import dev.xiaomu.crown.domain.text.CrownTextParser;
import dev.xiaomu.crown.domain.text.StyledText;
import dev.xiaomu.crown.domain.text.TextParseException;
import dev.xiaomu.crown.domain.text.TextParsePolicy;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * 语言渲染器：把语言键 + 位置参数解析为最终 Component。
 *
 * <p>模板本身允许颜色标签，但玩家名、称号正文等动态参数以字面量插入，
 * 不二次解析格式或变量，遵循 DESIGN.md §6.2 注入防护。</p>
 */
public final class CrownMessages {
    private static final TextParsePolicy TEMPLATE_POLICY =
            new TextParsePolicy(true, true, true, 4_096, 4_096);

    private final LanguageCatalog languages;
    private final FabricTextAdapter textAdapter;
    private final String prefix;

    public CrownMessages(
            LanguageCatalog languages,
            FabricTextAdapter textAdapter
    ) {
        this.languages = Objects.requireNonNull(languages, "languages");
        this.textAdapter = Objects.requireNonNull(textAdapter, "textAdapter");
        this.prefix = languages.text("prefix");
    }

    /** 渲染带前缀的消息；%0% 恒为前缀，其余参数按位置替换为字面量。 */
    public Component render(String key, String... args) {
        Objects.requireNonNull(key, "key");
        String template = languages.text(key);
        String[] positional = new String[args.length + 1];
        positional[0] = prefix;
        System.arraycopy(args, 0, positional, 1, args.length);
        return renderTemplate(template, positional);
    }

    /** 渲染不含前缀的原始模板，例如 GUI 标题或按钮文本。 */
    public Component renderRaw(String template, String... args) {
        Objects.requireNonNull(template, "template");
        return renderTemplate(template, args);
    }

    private Component renderTemplate(String template, String[] args) {
        String substituted = substitute(template, args);
        CrownTextParser parser = new CrownTextParser(TEMPLATE_POLICY);
        try {
            StyledText parsed = parser.parse(substituted);
            return textAdapter.component(parsed);
        } catch (TextParseException exception) {
            // 配置文本错误不能让服务器主线程的 GUI 任务失败。
            return Component.literal(sanitize(substituted));
        }
    }

    private static String substitute(String template, String[] args) {
        StringBuilder result = new StringBuilder(template.length() + 16);
        for (int index = 0; index < template.length(); index++) {
            char current = template.charAt(index);
            if (current != '%') {
                result.append(current);
                continue;
            }
            int end = template.indexOf('%', index + 1);
            if (end < 0) {
                result.append(current);
                continue;
            }
            String token = template.substring(index + 1, end);
            Integer position = parsePosition(token);
            if (position == null) {
                result.append(template, index, end + 1);
            } else if (position < args.length && args[position] != null) {
                result.append(sanitize(args[position]));
            }
            index = end;
        }
        return result.toString();
    }

    private static Integer parsePosition(String token) {
        if (token.isEmpty() || token.length() > 3) {
            return null;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return null;
            }
        }
        return Integer.parseInt(token);
    }

    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }
}