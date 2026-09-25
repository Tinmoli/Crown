package dev.xiaomu.crown.config;

import dev.xiaomu.crown.config.model.GuiVariables;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GuiVariablesTest {
    @Test void insertedTitleTokensAreNotExpandedAgain() {
        assertEquals("{price} / 50", GuiVariables.substitute("{title_preview} / {price}",
                Map.of("title_preview", "{price}", "price", "50")));
    }

    @Test void rendersCoinPriceAndPreservesLiteralSpecialCharacters() {
        assertEquals("50 称号币 / $5\\name", GuiVariables.substitute("{price} {currency} / {title}",
                Map.of("price", "50", "currency", "称号币", "title", "$5\\name")));
    }
}
