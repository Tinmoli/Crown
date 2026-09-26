package dev.xiaomu.crown.domain.text;

import java.util.ArrayList;

/** Output adapters for messages and consumers that discard Component styles. */
public final class TitleTextOutput {
    private TitleTextOutput() { }

    public static StyledText message(String template, String prefix, StyledText title) {
        String marker = "\uE000";
        String source = template.replace("%0%", prefix).replace("%1%", marker);
        var parser = new CrownTextParser(new TextParsePolicy(true, true, true, 4096, 4096));
        StyledText parsed = parser.parse(source);
        var output = new ArrayList<StyledText>();
        for (var segment : parsed.segments()) {
            String[] parts = segment.text().split(marker, -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) output.add(title);
                if (!parts[i].isEmpty()) output.add(new StyledText(
                        java.util.List.of(new StyledSegment(parts[i], segment.style()))));
            }
        }
        return StyledText.concatenate(output.toArray(StyledText[]::new));
    }

    /** For consumers that parse ampersand codes and &#RRGGBB strings. */
    public static String legacy(StyledText text) {
        var output = new StringBuilder();
        for (var segment : text.segments()) {
            output.append("&r");
            if (segment.style().color() != null) output.append('&').append(segment.style().color().toHex());
            for (var decoration : TextDecoration.values()) {
                if (segment.style().decorations().contains(decoration)) output.append('&').append(switch (decoration) {
                    case BOLD -> 'l';
                    case ITALIC -> 'o';
                    case UNDERLINED -> 'n';
                    case STRIKETHROUGH -> 'm';
                    case OBFUSCATED -> 'k';
                });
            }
            output.append(segment.text());
        }
        if (!text.isEmpty()) output.append("&r");
        return output.toString();
    }

    public static String miniMessage(StyledText text) {
        var root = net.kyori.adventure.text.Component.empty();
        for (var segment : text.segments()) {
            var style = net.kyori.adventure.text.format.Style.style();
            if (segment.style().color() != null) style.color(
                    net.kyori.adventure.text.format.TextColor.color(segment.style().color().packed()));
            for (var decoration : TextDecoration.values()) style.decoration(
                    net.kyori.adventure.text.format.TextDecoration.valueOf(decoration.name()),
                    segment.style().decorations().contains(decoration));
            root = root.append(net.kyori.adventure.text.Component.text(segment.text(), style.build()));
        }
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(root);
    }
}
