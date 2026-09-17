package com.rentalhub.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActorTest {

    @Test
    @DisplayName("with nobody named, changes are recorded as anonymous")
    void anonymousByDefault() {
        assertThat(AuditActor.current()).isEqualTo(AuditActor.ANONYMOUS);
    }

    @Test
    @DisplayName("scopes nest, and closing one restores whoever was acting before")
    void scopesNestAndRestore() {
        try (AuditActor.Scope request = AuditActor.as(AuditActor.user(7))) {
            assertThat(AuditActor.current()).isEqualTo("user:7");
            try (AuditActor.Scope job = AuditActor.as(AuditActor.system("nightly"))) {
                assertThat(AuditActor.current()).isEqualTo("system:nightly");
            }
            assertThat(AuditActor.current()).isEqualTo("user:7");
        }
        assertThat(AuditActor.current()).isEqualTo(AuditActor.ANONYMOUS);
    }

    @Test
    @DisplayName("a user's id can be read back from the recorded actor, and only a user's")
    void userIdOf() {
        assertThat(AuditActor.userIdOf("user:42")).contains(42L);
        assertThat(AuditActor.userIdOf("system:stale-listing-job")).isEmpty();
        assertThat(AuditActor.userIdOf("user:abc")).isEmpty();
        assertThat(AuditActor.userIdOf(null)).isEmpty();
    }
}
