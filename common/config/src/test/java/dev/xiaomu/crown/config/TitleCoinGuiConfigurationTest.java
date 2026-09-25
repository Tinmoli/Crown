package dev.xiaomu.crown.config;

import dev.xiaomu.crown.config.runtime.CrownConfigurationBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

final class TitleCoinGuiConfigurationTest {
    @TempDir Path root;

    @Test void eachPurchaseScreenHasOneConfirmationAndAnAlignedProcessingSlot() throws Exception {
        var snapshot = new CrownConfigurationBootstrap().initialize(root).snapshot();
        for (String screen : Set.of("purchase-confirm", "custom-confirm")) {
            var gui = snapshot.gui().require(screen);
            var actions = gui.buttons().values().stream().map(button -> button.action()).collect(Collectors.toSet());
            assertTrue(actions.contains("confirm"));
            assertTrue(actions.contains("cancel"));
            assertFalse(actions.stream().anyMatch(action -> action.startsWith("pay-")));
            assertEquals(gui.buttons().get("confirm").slot(), gui.buttons().get("processing").slot());
            assertTrue(gui.buttons().get("confirm").item().lore().stream()
                    .anyMatch(line -> line.contains("{title_coin_price}")));
        }
        assertEquals(50, snapshot.core().customTitle().payment().titleCoinPrice());
        assertEquals(50, snapshot.catalog().find("veteran").orElseThrow().payment().titleCoinPrice());
    }

    @Test void optionalSaleAndDurationSurviveReloadForCustomProducts() throws Exception {
        var bootstrap = new CrownConfigurationBootstrap();
        bootstrap.initialize(root);
        Files.writeString(root.resolve("titles.yml"), """
                config-version: 3
                titles:
                  custom:
                    text: "Test"
                    icon: "minecraft:name_tag"
                    price: 7
                    duration:
                      days: 3
                    sale:
                      global-stock: 2
                      per-player-limit: 1
                """);
        for (int i = 0; i < 2; i++) {
            var product = bootstrap.initialize(root).snapshot().catalog().find("custom").orElseThrow();
            assertEquals(7, product.payment().titleCoinPrice());
            assertEquals(3, product.duration().days());
            assertEquals(2, product.sale().globalStock());
            assertEquals(1, product.sale().perPlayerLimit());
        }
    }

    @Test void invalidCoinPricesFailInsteadOfBeingReplacedWithDefaults() throws Exception {
        var bootstrap = new CrownConfigurationBootstrap();
        bootstrap.initialize(root);
        Path config = root.resolve("config.yml");
        String original = Files.readString(config);
        for (String price : Set.of("-1", "1.5", "9223372036854775808", "oops")) {
            Files.writeString(config, original.replace("price: 50", "price: " + price));
            assertThrows(IllegalArgumentException.class, () -> bootstrap.initialize(root), price);
        }
    }
}
