package com.rentalhub.service;

import com.rentalhub.domain.model.Villa;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.ListingHistoryEntry.FieldChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** How two snapshots of a listing are compared, without a database. */
class ListingHistoryServiceTest {

    @Test
    @DisplayName("only fields whose value changed are reported, and a price's decimals don't count as a change")
    void onlyRealChanges() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("title", "Sea breeze villa");
        before.put("pricePerNight", new BigDecimal("12000.0000"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("title", "Sunset villa");
        after.put("pricePerNight", new BigDecimal("12000.00"));

        List<FieldChange> changes = ListingHistoryService.differences(before, after);

        assertThat(changes).containsExactly(new FieldChange("title", "Sea breeze villa", "Sunset villa"));
    }

    @Test
    @DisplayName("a new listing's entry lists every field that was set, type-specific ones by their request name")
    void creationListsEverySetField() {
        Villa villa = new Villa();
        villa.setTitle("Sea breeze villa");
        villa.setPricePerNight(new BigDecimal("12000.00"));
        villa.setCurrency(Currency.INR);
        villa.setHasPool(true);

        List<FieldChange> changes = ListingHistoryService.differences(Map.of(), ListingHistoryService.snapshot(villa));

        assertThat(changes)
                .contains(new FieldChange("title", null, "Sea breeze villa"))
                .contains(new FieldChange("attributes[hasPool]", null, true))
                .extracting(FieldChange::field)
                .doesNotContain("address", "attributes[plotAreaSqm]");   // never set: nothing to report
    }
}
