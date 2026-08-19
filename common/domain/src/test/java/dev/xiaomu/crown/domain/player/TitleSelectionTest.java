package dev.xiaomu.crown.domain.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TitleSelectionTest {
    @Test
    void modelsAllThreeSelectionStates() {
        UUID entryId = UUID.randomUUID();

        assertEquals(SelectionType.DEFAULT,
                TitleSelection.defaultTitle().type());
        assertEquals(SelectionType.NONE, TitleSelection.none().type());
        assertEquals(entryId,
                TitleSelection.owned(entryId).ownedEntryId().orElseThrow());
        assertFalse(TitleSelection.none().ownedEntryId().isPresent());
        assertTrue(TitleSelection.owned(entryId)
                .ownedEntryId().isPresent());
    }

    @Test
    void rejectsAmbiguousStates() {
        assertThrows(IllegalArgumentException.class,
                () -> new TitleSelection(SelectionType.OWNED, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TitleSelection(
                        SelectionType.NONE, UUID.randomUUID()));
    }
}