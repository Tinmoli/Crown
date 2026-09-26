package dev.xiaomu.crown.domain.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Normalize legacy codes, then parse MiniMessage colors and decorations. */
public final class CrownTextParser {
    private static final TagResolver COLORS = StandardTags.color();
    private static final TagResolver DECORATIONS = StandardTags.decorations();
    private static final TagResolver EFFECTS = TagResolver.resolver(
            StandardTags.gradient(), StandardTags.rainbow(), StandardTags.transition());
    private static final TagResolver TAGS = TagResolver.resolver(
            COLORS, DECORATIONS, EFFECTS, StandardTags.reset());
    private static final String[] LEGACY_COLORS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
    };
    private final TextParsePolicy policy;
    private final MiniMessage mini;

    public CrownTextParser(TextParsePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        mini = MiniMessage.builder().tags(new CheckedTags()).build();
    }

    public StyledText parse(String source) {
        Objects.requireNonNull(source, "source");
        if (source.length() > policy.maximumSourceLength()) throw error(TextParseError.SOURCE_TOO_LONG);
        if (source.codePoints().anyMatch(Character::isISOControl)) throw error(TextParseError.CONTROL_CHARACTER);
        Component component = mini.deserialize(normalize(source));
        var segments = new ArrayList<StyledSegment>();
        flatten(component, TextStyle.EMPTY, segments);
        StyledText result = new StyledText(segments);
        if (result.visibleCodePointCount() > policy.maximumVisibleLength()) throw error(TextParseError.VISIBLE_TOO_LONG);
        return result;
    }

    private String normalize(String source) {
        var result = new StringBuilder();
        for (int i = 0; i < source.length();) {
            char marker = source.charAt(i);
            if (marker == '\\' && i + 1 < source.length()) {
                result.append(marker).append(source.charAt(i + 1));
                i += 2;
                continue;
            }
            // Do not rewrite hex colors inside MiniMessage tag arguments.
            if (marker == '<') {
                int end = source.indexOf('>', i + 1);
                if (end >= 0) {
                    result.append(source, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            if ((marker == '&' || marker == '\u00a7') && i + 1 < source.length()) {
                char code = Character.toLowerCase(source.charAt(i + 1));
                if (code == 'x') {
                    if (i + 14 > source.length()) throw error(TextParseError.INCOMPLETE_RGB);
                    var digits = new StringBuilder();
                    for (int j = 0; j < 6; j++) {
                        int at = i + 2 + j * 2;
                        if (source.charAt(at) != marker || Character.digit(source.charAt(at + 1), 16) < 0) {
                            throw error(TextParseError.MALFORMED_RGB);
                        }
                        digits.append(source.charAt(at + 1));
                    }
                    requireRgb();
                    result.append("<crown_rgb_").append(digits).append('>');
                    i += 14;
                    continue;
                }
                if (code == '#') {
                    if (i + 8 > source.length()) throw error(TextParseError.INCOMPLETE_RGB);
                    String digits = source.substring(i + 2, i + 8);
                    if (!RgbColor.isHex(digits)) throw error(TextParseError.MALFORMED_RGB);
                    requireRgb();
                    result.append("<#").append(digits).append('>');
                    i += 8;
                    continue;
                }
                int color = Character.digit(code, 16);
                if (color >= 0) {
                    requireLegacy();
                    result.append("<crown_legacy_").append(code).append('>');
                    i += 2;
                    continue;
                }
                String tag = switch (code) {
                    case 'k' -> "obfuscated";
                    case 'l' -> "bold";
                    case 'm' -> "strikethrough";
                    case 'n' -> "underlined";
                    case 'o' -> "italic";
                    case 'r' -> "reset";
                    default -> null;
                };
                if (tag != null) {
                    requireLegacy();
                    result.append('<').append(tag).append('>');
                    i += 2;
                    continue;
                }
            }
            if (marker == '#' && i + 7 <= source.length() && RgbColor.isHex(source.substring(i + 1, i + 7))) {
                requireRgb();
                result.append('<').append(source, i, i + 7).append('>');
                i += 7;
                continue;
            }
            result.append(marker);
            i++;
        }
        return result.toString();
    }

    private final class CheckedTags implements TagResolver {
        @Override public boolean has(String name) {
            return TAGS.has(name) || name.startsWith("crown_legacy_") || name.startsWith("crown_rgb_");
        }

        @Override public Tag resolve(String name, ArgumentQueue arguments, Context context) {
            boolean emptyArguments = !arguments.hasNext();
            if (emptyArguments && List.of("color", "colour", "c").contains(name)) return null;
            if (name.startsWith("crown_legacy_") || name.startsWith("crown_rgb_")) {
                net.kyori.adventure.text.format.TextColor color;
                if (name.startsWith("crown_legacy_")) {
                    requireLegacy();
                    String code = name.substring("crown_legacy_".length());
                    if (code.length() != 1 || Character.digit(code.charAt(0), 16) < 0) throw error(TextParseError.MALFORMED_COLOR_TAG);
                    color = NamedTextColor.NAMES.value(LEGACY_COLORS[Character.digit(code.charAt(0), 16)]);
                } else {
                    requireRgb();
                    String hex = name.substring("crown_rgb_".length());
                    if (!RgbColor.isHex(hex)) throw error(TextParseError.MALFORMED_RGB);
                    color = net.kyori.adventure.text.format.TextColor.color(RgbColor.parse(hex).packed());
                }
                var style = Style.style().color(color);
                for (var decoration : net.kyori.adventure.text.format.TextDecoration.values()) style.decoration(decoration, false);
                return Tag.styling(builder -> builder.merge(style.build()));
            }
            if (EFFECTS.has(name)) {
                if (!policy.allowGradient()) throw error(TextParseError.GRADIENT_DISABLED);
                requireRgb();
            } else if (COLORS.has(name)) {
                String value = name;
                if (List.of("color", "colour", "c").contains(name) && arguments.hasNext()) value = arguments.peek().value();
                if (value.startsWith("#")) requireRgb(); else requireLegacy();
            } else if (DECORATIONS.has(name) || name.equals("reset")) {
                requireLegacy();
            }
            try {
                return TAGS.resolve(name, arguments, context);
            } catch (ParsingException exception) {
                // MiniMessage probes closing tag names without their opening arguments.
                if (emptyArguments) return null;
                throw error(EFFECTS.has(name) ? TextParseError.MALFORMED_GRADIENT_TAG : TextParseError.MALFORMED_COLOR_TAG);
            }
        }
    }

    private static void flatten(Component component, TextStyle inherited, List<StyledSegment> output) {
        var style = component.style();
        var decorations = EnumSet.noneOf(TextDecoration.class);
        decorations.addAll(inherited.decorations());
        for (TextDecoration decoration : TextDecoration.values()) {
            var key = net.kyori.adventure.text.format.TextDecoration.valueOf(decoration.name());
            switch (style.decoration(key)) {
                case TRUE -> decorations.add(decoration);
                case FALSE -> decorations.remove(decoration);
                case NOT_SET -> { }
            }
        }
        TextStyle resolved = new TextStyle(style.color() == null ? inherited.color()
                : RgbColor.fromPacked(style.color().value()), decorations);
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            if (!output.isEmpty() && output.getLast().style().equals(resolved)) {
                StyledSegment previous = output.removeLast();
                output.add(new StyledSegment(previous.text() + text.content(), resolved));
            } else output.add(new StyledSegment(text.content(), resolved));
        }
        for (Component child : component.children()) flatten(child, resolved, output);
    }

    private void requireLegacy() {
        if (!policy.allowLegacyFormatting()) throw error(TextParseError.LEGACY_DISABLED);
    }
    private void requireRgb() {
        if (!policy.allowRgb()) throw error(TextParseError.RGB_DISABLED);
    }
    private static TextParseException error(TextParseError error) {
        return new TextParseException(error, error.name().toLowerCase(Locale.ROOT), 0);
    }
}
