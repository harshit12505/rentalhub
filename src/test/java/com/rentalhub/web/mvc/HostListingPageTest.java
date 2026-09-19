package com.rentalhub.web.mvc;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.factory.AttributeSpec;
import com.rentalhub.factory.PropertyFactory;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;

/**
 * The host's "list a place" form. Its type-specific inputs are generated from the factory's
 * AttributeSpecs, so the test asks the factory what to expect rather than listing fields itself:
 * a new property type is covered here the day it is added.
 */
class HostListingPageTest extends PageTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyFactory factory;

    private MockHttpSession host;

    @BeforeEach
    void signInAsHost() throws Exception {
        host = signIn(users.save(TestRequests.host()).getId());
    }

    @Test
    @DisplayName("the form has one group of inputs per property type, generated from that type's own specs")
    void everyTypeHasItsFields() throws Exception {
        String page = html(mvc.perform(get("/host/listings/new").session(host)));

        for (var type : factory.supportedTypes()) {
            assertThat(page).contains("data-type=\"" + type.name() + "\"");
            for (AttributeSpec spec : factory.attributeSpecs(type)) {
                assertThat(page).contains("name=\"attributes[" + spec.name() + "]\"");
            }
        }
        List<String> groups = Pattern.compile("<fieldset[^>]*>").matcher(page).results().map(MatchResult::group).toList();
        assertThat(groups).hasSize(factory.supportedTypes().size());
        assertThat(groups).as("every group but the chosen type's is disabled, so it is not sent")
                .filteredOn(group -> group.contains("disabled")).hasSize(groups.size() - 1);
    }

    @Test
    @DisplayName("?type= chooses which type's fields are shown, which is how the form works without JavaScript")
    void typeFromTheAddress() throws Exception {
        var last = factory.supportedTypes().stream().reduce((first, second) -> second).orElseThrow();

        String page = html(mvc.perform(get("/host/listings/new").param("type", last.name()).session(host)));

        assertThat(page).containsPattern("<option value=\"" + last.name() + "\" selected=\"selected\"")
                .containsPattern("<fieldset class=\"[^\"]*\"\\s+data-type=\"" + last.name() + "\">");
    }

    @Test
    @DisplayName("a valid listing is published, and the host lands on its page")
    void publish() throws Exception {
        MvcResult created = mvc.perform(listing(TestRequests.validVilla()))
                .andExpect(redirectedUrlPattern("/listings/*"))
                .andReturn();

        assertThat(html(follow(created))).contains("Your listing is live.", "Test villa", "Plot area (m²)");
    }

    @Test
    @DisplayName("a refused listing comes back as typed, each problem beside its field, in Hindi")
    void refusedInHindi() throws Exception {
        PropertyRequest noTitle = TestRequests.validVilla();
        noTitle.setTitle(" ");

        MvcResult refused = mvc.perform(listing(noTitle).param("lang", "hi"))
                .andExpect(redirectedUrl("/host/listings/new"))
                .andReturn();

        assertThat(html(follow(refused))).contains("<html lang=\"hi\"", "यह फ़ील्ड ज़रूरी है।", "is-invalid",
                "value=\"Goa\"");
    }

    @Test
    @DisplayName("a type's own rule (a villa's plot too small) is shown beside that type's field")
    void typeRuleBesideItsField() throws Exception {
        PropertyRequest smallPlot = TestRequests.validVilla();
        smallPlot.getAttributes().put("plotAreaSqm", "50");

        MvcResult refused = mvc.perform(listing(smallPlot)).andExpect(redirectedUrl("/host/listings/new")).andReturn();

        String page = html(follow(refused));
        assertThat(page).contains("A villa needs a plot of at least 100 m².");
        assertThat(Pattern.compile("<input[^<>]*name=\"attributes\\[plotAreaSqm]\"[^<>]*>").matcher(page).results()
                .map(MatchResult::group).toList())
                .singleElement().asString().contains("is-invalid", "value=\"50\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM properties", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a guest is told only hosts can list, and a guest's post is refused")
    void guestsCannotList() throws Exception {
        MockHttpSession guest = signIn(users.save(TestRequests.guest()).getId());

        assertThat(html(mvc.perform(get("/host/listings/new").session(guest))))
                .contains("Only hosts can list a place.").doesNotContain("name=\"title\"");

        MvcResult refused = mvc.perform(form("/host/listings/new", guest).params(formOf(TestRequests.validVilla())))
                .andExpect(redirectedUrl("/host/listings/new"))
                .andReturn();
        assertThat(html(follow(refused))).contains("Only hosts can list a place.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM properties", Integer.class)).isZero();
    }

    private MockHttpServletRequestBuilder listing(PropertyRequest request) {
        return form("/host/listings/new", host).params(formOf(request));
    }

    /** The request as the browser sends the form: every field a text parameter. */
    private static MultiValueMap<String, String> formOf(PropertyRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("type", request.getType().name());
        form.add("title", request.getTitle());
        form.add("description", request.getDescription());
        form.add("city", request.getCity());
        form.add("country", request.getCountry());
        form.add("pricePerNight", request.getPricePerNight().toPlainString());
        form.add("currency", request.getCurrency().name());
        form.add("maxGuests", String.valueOf(request.getMaxGuests()));
        form.add("bedrooms", String.valueOf(request.getBedrooms()));
        form.add("bathrooms", String.valueOf(request.getBathrooms()));
        request.getAttributes().forEach((name, value) -> form.add("attributes[" + name + "]", value));
        return form;
    }
}
