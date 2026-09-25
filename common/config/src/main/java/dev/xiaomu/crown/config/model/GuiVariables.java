package dev.xiaomu.crown.config.model;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Replaces only template tokens; inserted values are never expanded again. */
public final class GuiVariables {
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z0-9_]+)}");

    private GuiVariables() { }

    public static String substitute(String template, Map<String, String> variables) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(variables, "variables");
        var matcher = TOKEN.matcher(template);
        var result = new StringBuilder();
        while (matcher.find()) {
            String value = variables.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value == null ? "" : value));
        }
        return matcher.appendTail(result).toString();
    }
}
