package dev.xiaomu.crown.domain.catalog;

import dev.xiaomu.crown.domain.text.StyledText;

import java.util.Objects;

/** 前缀、正文、后缀的源码与已验证解析结果。 */
public record TitleContent(
        String prefixSource,
        String textSource,
        String suffixSource,
        StyledText prefix,
        StyledText text,
        StyledText suffix
) {
    public TitleContent {
        prefixSource = Objects.requireNonNull(prefixSource, "prefixSource");
        textSource = Objects.requireNonNull(textSource, "textSource");
        suffixSource = Objects.requireNonNull(suffixSource, "suffixSource");
        prefix = Objects.requireNonNull(prefix, "prefix");
        text = Objects.requireNonNull(text, "text");
        suffix = Objects.requireNonNull(suffix, "suffix");
        if (text.isEmpty()) {
            throw new IllegalArgumentException(
                    "Title body must contain visible text");
        }
    }

    public StyledText fullText() {
        return StyledText.concatenate(prefix, text, suffix);
    }

    public String plainText() {
        return fullText().plainText();
    }
}