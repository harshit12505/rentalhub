package com.rentalhub.ai;

import com.rentalhub.domain.model.Villa;
import com.rentalhub.domain.model.enums.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of indexing that must never drift: what text a listing turns into, the hash that
 * decides whether it is re-embedded, and the document id that keeps one listing to one
 * document.
 */
class ListingEmbeddingServiceTest {

    private static Villa villa() {
        Villa villa = new Villa();
        villa.setTitle("Sea Breeze Villa");
        villa.setDescription("Steps from a quiet beach, with a shaded garden.");
        villa.setCity("Goa");
        villa.setCountry("India");
        villa.setPricePerNight(new BigDecimal("8500.00"));
        villa.setCurrency(Currency.INR);
        villa.setMaxGuests(6);
        villa.setBedrooms(3);
        villa.setBathrooms(2);
        villa.setPlotAreaSqm(new BigDecimal("450.00"));
        villa.setHasPool(true);
        return villa;
    }

    @Test
    @DisplayName("the embedded text carries everything a guest would search by")
    void textDescribesTheListing() {
        String text = ListingEmbeddingService.textFor(villa());

        assertThat(text)
                .contains("villa in Goa, India")
                .contains("Sea Breeze Villa")
                .contains("quiet beach")
                .contains("Sleeps 6")
                .contains("8500.00 INR per night")
                .as("type-specific attributes travel too, with no if on the type")
                .contains("hasPool: true");
    }

    @Test
    @DisplayName("the hash changes only when the text does: that is what saves the quota")
    void hashFollowsTheText() {
        Villa villa = villa();
        String before = ListingEmbeddingService.hashOf(ListingEmbeddingService.textFor(villa));

        villa.setAvailableUntil(java.time.LocalDate.of(2030, 1, 1));
        assertThat(ListingEmbeddingService.hashOf(ListingEmbeddingService.textFor(villa)))
                .as("a change the text does not mention costs no embedding call")
                .isEqualTo(before);

        villa.setPricePerNight(new BigDecimal("8500.0000"));
        assertThat(ListingEmbeddingService.hashOf(ListingEmbeddingService.textFor(villa)))
                .as("the same money, with the trailing zeros NUMERIC(19,4) adds, is the same text")
                .isEqualTo(before);

        villa.setTitle("Sea Breeze Villa, now with a pool");
        assertThat(ListingEmbeddingService.hashOf(ListingEmbeddingService.textFor(villa)))
                .isNotEqualTo(before);

        assertThat(before).hasSize(64);
    }

    @Test
    @DisplayName("a listing always gets the same document id, so an edit replaces rather than duplicates")
    void documentIdIsDerivedFromTheListing() {
        assertThat(ListingEmbeddingService.documentIdFor(42))
                .isEqualTo(ListingEmbeddingService.documentIdFor(42))
                .isNotEqualTo(ListingEmbeddingService.documentIdFor(43));
    }
}
