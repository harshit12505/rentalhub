package com.rentalhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintViolationsTest {

    private final DataIntegrityViolationException duplicateReview = new DataIntegrityViolationException(
            "could not execute statement", new RuntimeException("wrapped by Hibernate", new SQLException(
                    "ERROR: duplicate key value violates unique constraint \"uq_review_author_property\"", "23505")));

    @Test
    @DisplayName("the named constraint is found however deep the driver's error is wrapped")
    void findsTheNamedConstraint() {
        assertThat(ConstraintViolations.violated(duplicateReview, ConstraintViolations.UNIQUE_VIOLATION,
                "uq_review_author_property")).isTrue();
    }

    @Test
    @DisplayName("another constraint, or another kind of violation, is not mistaken for it")
    void noFalseMatches() {
        assertThat(ConstraintViolations.violated(duplicateReview, ConstraintViolations.UNIQUE_VIOLATION,
                "users_email_key")).isFalse();
        assertThat(ConstraintViolations.violated(duplicateReview, ConstraintViolations.EXCLUSION_VIOLATION,
                "uq_review_author_property")).isFalse();
    }
}
