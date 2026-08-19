package dev.xiaomu.crown.config.lang;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 不可变语言缓存，按当前语言、中文、英文、原始键依次回退。 */
public final class LanguageCatalog {
    private final String selectedLanguage;
    private final Map<String, Map<String, String>> languages;

    public LanguageCatalog(
            String selectedLanguage,
            Map<String, Map<String, String>> languages
    ) {
        this.selectedLanguage = requireLanguageId(selectedLanguage);
        Objects.requireNonNull(languages, "languages");

        var copy = new LinkedHashMap<String, Map<String, String>>();
        languages.forEach((id, entries) -> copy.put(
                requireLanguageId(id),
                Map.copyOf(Objects.requireNonNull(entries, "entries"))));
        this.languages = Map.copyOf(copy);
    }

    public String selectedLanguage() {
        return selectedLanguage;
    }

    public Map<String, Map<String, String>> languages() {
        return languages;
    }

    public String text(String key) {
        Objects.requireNonNull(key, "key");
        String selected = lookup(selectedLanguage, key);
        if (selected != null) {
            return selected;
        }
        String chinese = lookup("zh_cn", key);
        if (chinese != null) {
            return chinese;
        }
        String english = lookup("en_us", key);
        return english == null ? key : english;
    }

    private String lookup(String language, String key) {
        Map<String, String> entries = languages.get(language);
        return entries == null ? null : entries.get(key);
    }

    public static String requireLanguageId(String value) {
        Objects.requireNonNull(value, "language");
        if (!value.matches("[a-z0-9_-]{2,32}")) {
            throw new IllegalArgumentException(
                    "Invalid language ID: " + value);
        }
        return value;
    }
}