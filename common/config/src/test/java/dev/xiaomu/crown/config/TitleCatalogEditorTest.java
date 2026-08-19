package dev.xiaomu.crown.config;

import dev.xiaomu.crown.config.edit.TitleCatalogEditor;
import dev.xiaomu.crown.config.io.SafeYaml;
import dev.xiaomu.crown.config.runtime.CrownConfigurationBootstrap;
import dev.xiaomu.crown.config.runtime.RuntimeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleCatalogEditorTest {
    @TempDir
    Path temporary;

    @Test
    void createsCompleteDisabledDraftAndCanEditNestedField()
            throws Exception {
        Fixture fixture = fixture();
        AtomicInteger reloads = new AtomicInteger();

        fixture.editor.create(
                fixture.file,
                fixture.snapshot.core().safety(),
                "new_title",
                reloads::incrementAndGet);

        Map<String, Object> created = definition(fixture.file, "new_title");
        assertEquals(false, created.get("enabled"));
        assertEquals(false, created.get("visible"));
        assertEquals("new_title", created.get("text"));
        assertEquals("minecraft:name_tag", created.get("icon"));

        fixture.editor.set(
                fixture.file,
                fixture.snapshot.core().safety(),
                "new_title",
                "sale.global-stock",
                25L,
                reloads::incrementAndGet);

        Map<String, Object> edited = definition(fixture.file, "new_title");
        Number stock = (Number) nested(
                edited, "sale").get("global-stock");
        assertEquals(25L, stock.longValue());
        assertEquals(2, reloads.get());
    }

    @Test
    void invalidCandidateNeverReplacesOriginal() throws Exception {
        Fixture fixture = fixture();
        String original = Files.readString(
                fixture.file, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () ->
                fixture.editor.set(
                        fixture.file,
                        fixture.snapshot.core().safety(),
                        "veteran",
                        "text",
                        "",
                        () -> {
                            throw new AssertionError(
                                    "reload must not run");
                        }));

        assertEquals(original, Files.readString(
                fixture.file, StandardCharsets.UTF_8));
    }

    @Test
    void noOpDoesNotRewriteOrReloadAndCommentsRemain()
            throws Exception {
        Fixture fixture = fixture();
        String original = Files.readString(
                fixture.file, StandardCharsets.UTF_8);
        AtomicInteger reloads = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () ->
                fixture.editor.set(
                        fixture.file,
                        fixture.snapshot.core().safety(),
                        "veteran",
                        "enabled",
                        true,
                        reloads::incrementAndGet));

        assertEquals(0, reloads.get());
        assertEquals(original, Files.readString(
                fixture.file, StandardCharsets.UTF_8));

        fixture.editor.set(
                fixture.file,
                fixture.snapshot.core().safety(),
                "veteran",
                "enabled",
                false,
                reloads::incrementAndGet);
        String edited = Files.readString(
                fixture.file, StandardCharsets.UTF_8);
        assertTrue(edited.contains("enabled: false"));
    }

    @Test
    void reloadFailureRestoresOriginalFile() throws Exception {
        Fixture fixture = fixture();
        String original = Files.readString(
                fixture.file, StandardCharsets.UTF_8);
        AtomicInteger reloads = new AtomicInteger();

        assertThrows(IOException.class, () ->
                fixture.editor.set(
                        fixture.file,
                        fixture.snapshot.core().safety(),
                        "veteran",
                        "enabled",
                        false,
                        () -> {
                            if (reloads.getAndIncrement() == 0) {
                                throw new IOException("candidate rejected");
                            }
                        }));

        assertEquals(2, reloads.get());
        assertEquals(original, Files.readString(
                fixture.file, StandardCharsets.UTF_8));
    }

    @Test
    void deleteRejectsMissingIdAndRemovesExistingDefinition()
            throws Exception {
        Fixture fixture = fixture();
        String original = Files.readString(
                fixture.file, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () ->
                fixture.editor.delete(
                        fixture.file,
                        fixture.snapshot.core().safety(),
                        "missing",
                        () -> {
                        }));
        assertEquals(original, Files.readString(
                fixture.file, StandardCharsets.UTF_8));

        fixture.editor.delete(
                fixture.file,
                fixture.snapshot.core().safety(),
                "event_winner",
                () -> {
                });
        Map<String, Object> root = new SafeYaml().loadMap(
                Files.readString(fixture.file, StandardCharsets.UTF_8));
        assertFalse(titles(root).containsKey("event_winner"));
        assertTrue(titles(root).containsKey("veteran"));
    }

    private Fixture fixture() throws IOException {
        var report = new CrownConfigurationBootstrap()
                .initialize(temporary);
        return new Fixture(
                temporary.resolve("titles.yml"),
                report.snapshot(),
                new TitleCatalogEditor());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> definition(
            Path file,
            String id
    ) throws IOException {
        Map<String, Object> root = new SafeYaml().loadMap(
                Files.readString(file, StandardCharsets.UTF_8));
        return (Map<String, Object>) titles(root).get(id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> titles(
            Map<String, Object> root
    ) {
        return (Map<String, Object>) root.get("titles");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(
            Map<String, Object> root,
            String key
    ) {
        return (Map<String, Object>) root.get(key);
    }

    private record Fixture(
            Path file,
            RuntimeSnapshot snapshot,
            TitleCatalogEditor editor
    ) {
    }
}