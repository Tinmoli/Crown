package dev.xiaomu.crown.config.sync;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从内置 YML 模板提取注释，并把标准注释重新插入规范化输出。
 *
 * <p>该工具不尝试保留用户自定义注释；托管字段始终使用当前版本内置的
 * 简体中文注释。titles 开放映射中的自定义商品使用模板首个商品作为
 * 注释 Schema。</p>
 */
public final class TemplateComments {
    private static final String HEADER = "$header";

    private TemplateComments() {
    }

    public static String apply(String template, String dumped) {
        Map<String, List<String>> comments = extract(template);
        List<String> output = new ArrayList<>();
        List<String> header = comments.get(HEADER);
        if (header != null) {
            output.addAll(header);
        }

        var stack = new ArrayDeque<PathPart>();
        for (String line : dumped.split("\\R", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            ParsedKey parsed = key(line);
            if (parsed == null) {
                output.add(line);
                continue;
            }

            while (!stack.isEmpty()
                    && stack.peekLast().indent() >= parsed.indent()) {
                stack.removeLast();
            }
            stack.addLast(new PathPart(parsed.indent(), parsed.key()));
            String path = path(stack);
            List<String> block = comments.get(path);
            if (block == null) {
                block = comments.get(titleWildcard(path));
            }
            if (block != null && !block.isEmpty()) {
                if (!output.isEmpty()
                        && !output.get(output.size() - 1).isEmpty()) {
                    output.add("");
                }
                output.addAll(block);
            }
            output.add(line);
        }
        return String.join("\n", output) + '\n';
    }

    private static Map<String, List<String>> extract(String template) {
        var result = new HashMap<String, List<String>>();
        var stack = new ArrayDeque<PathPart>();
        var pending = new ArrayList<String>();
        boolean sawKey = false;

        for (String line : template.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("#")) {
                pending.add(line);
                continue;
            }
            if (trimmed.isEmpty()) {
                if (!pending.isEmpty()) {
                    pending.add("");
                }
                continue;
            }

            ParsedKey parsed = key(line);
            if (parsed == null) {
                pending.clear();
                continue;
            }
            while (!stack.isEmpty()
                    && stack.peekLast().indent() >= parsed.indent()) {
                stack.removeLast();
            }
            stack.addLast(new PathPart(parsed.indent(), parsed.key()));
            String path = path(stack);
            if (!sawKey && !pending.isEmpty()) {
                result.put(HEADER, trimBlankEdges(pending));
            } else if (!pending.isEmpty()) {
                result.put(path, trimBlankEdges(pending));
            }
            pending.clear();
            sawKey = true;
        }
        return result;
    }

    private static String titleWildcard(String path) {
        String prefix = "titles.";
        if (!path.startsWith(prefix)) {
            return path;
        }
        int next = path.indexOf('.', prefix.length());
        if (next < 0) {
            return "titles.veteran";
        }
        return "titles.veteran" + path.substring(next);
    }

    private static List<String> trimBlankEdges(List<String> source) {
        int start = 0;
        int end = source.size();
        while (start < end && source.get(start).isEmpty()) {
            start++;
        }
        while (end > start && source.get(end - 1).isEmpty()) {
            end--;
        }
        return List.copyOf(source.subList(start, end));
    }

    private static ParsedKey key(String line) {
        int indent = line.length() - line.stripLeading().length();
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("-")) {
            return null;
        }

        int separator = mappingSeparator(trimmed);
        if (separator <= 0) {
            return null;
        }
        String raw = trimmed.substring(0, separator).trim();
        String key = unquote(raw);
        if (key.isEmpty()) {
            return null;
        }
        return new ParsedKey(indent, key);
    }

    private static int mappingSeparator(String value) {
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character == '\'' || character == '"')) {
                if (quote == 0) {
                    quote = character;
                } else if (quote == character) {
                    quote = 0;
                }
            } else if (character == ':' && quote == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String path(ArrayDeque<PathPart> stack) {
        return stack.stream()
                .map(PathPart::key)
                .reduce((left, right) -> left + '.' + right)
                .orElse("");
    }

    private record ParsedKey(int indent, String key) {
    }

    private record PathPart(int indent, String key) {
    }
}