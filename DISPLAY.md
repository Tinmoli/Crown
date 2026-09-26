# Crown Display and Colors

中文显示说明：[DISPLAY_zh.md](DISPLAY_zh.md) · English entry: [README.md](README.md)

## Display modes

Configure chat, TAB, and nametag independently in `config.yml`:

```yaml
display:
  channels:
    chat: "vanilla"
    tab: "placeholder"
    nametag: "placeholder"
```

| Mode | Behavior |
|---|---|
| `vanilla` | Crown uses Minecraft's native server display path |
| `placeholder` | An external chat, TAB, or nametag mod reads the variables |
| `disabled` | Crown does not actively display the title; variables remain available |

The default is `placeholder`. A placeholder API installation alone does not configure another mod; that mod must be told which variable to use.

## Supported input formats

Examples accepted by products and custom titles:

```text
&aGreen title
§bBlue title
&#FF8800Orange title
&x&F&F&8&8&0&0Orange title
<red>Red title</red>
<#AA55FF>Purple title</#AA55FF>
<gradient:#FF5555:#5555FF>Gradient</gradient>
<rainbow>Rainbow</rainbow>
&6&lGold&r&aNova
```

`allow-formatting` controls legacy colors, decorations, and named MiniMessage styles. `allow-rgb` controls hexadecimal colors. `allow-gradient` controls gradients, rainbows, and transitions and requires RGB support. Click, hover, and line-break tags are not enabled.

Native Minecraft text assigns one color to each character. A gradient or rainbow is therefore static and character-based; a single character cannot contain multiple colors in ordinary vanilla text and there is no built-in animation.

## Placeholder variables

With Text Placeholder API installed:

| Variable | Value |
|---|---|
| `%crown:title%` | Complete native styled title, including prefix and suffix |
| `%crown:title_text%` | Styled title body |
| `%crown:title_prefix%` / `%crown:title_suffix%` | Styled prefix or suffix |
| `%crown:title_legacy%` | Complete title serialized as `&` and `&#RRGGBB` codes |
| `%crown:title_minimessage%` | Complete title serialized as MiniMessage |
| `%crown:title_plain%` | Complete title without formatting |
| `%crown:title_id%` | Entry UUID, `default`, or empty |
| `%crown:title_definition%` | Product ID |
| `%crown:title_state%` | `default`, `owned`, or `none` |
| `%crown:title_expires%` | Remaining time or permanent |
| `%crown:title_coin%` / `%crown:title_coin_raw%` | Formatted or numeric coin balance |

Use `%crown:title%` when the receiving mod preserves native components. Use `title_legacy` or `title_minimessage` when it accepts strings and parses those formats itself.

## TAB Fabric 6.0.3

This TAB version drops native component styles in its generic placeholder path. Use the compatibility string variable:

```yaml
_DEFAULT_:
  tabprefix: "%crown:title_legacy% "
  tagprefix: "%crown:title_legacy% "
```

Update every TAB group that overrides these fields, then reload TAB. Crown's own GUI, purchase messages, equip messages, and vanilla display paths continue to use native styled text.
