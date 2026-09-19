// The "list a place" form: show only the chosen property type's own fields.
//
// Every type's group of fields is already on the page (generated from that type's
// AttributeSpecs); this only swaps which one is visible. A disabled fieldset's inputs are not
// submitted, so the server never receives another type's fields. Without JavaScript the page
// still works: the server shows the right group, and a button reloads it for another type.
(function () {
    'use strict';
    var select = document.querySelector('[data-listing-type]');
    if (!select) {
        return;
    }
    function showFieldsFor(type) {
        document.querySelectorAll('fieldset.type-fields').forEach(function (fieldset) {
            var chosen = fieldset.getAttribute('data-type') === type;
            fieldset.hidden = !chosen;
            fieldset.disabled = !chosen;
        });
    }
    select.addEventListener('change', function () {
        showFieldsFor(select.value);
    });
    showFieldsFor(select.value);
})();
