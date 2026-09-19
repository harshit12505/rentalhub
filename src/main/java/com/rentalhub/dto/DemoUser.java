package com.rentalhub.dto;

import com.rentalhub.domain.model.enums.UserRole;

/**
 * A user as the "sign in as" list shows them.
 *
 * @param role whether they can list places (HOST) or only book them (GUEST)
 */
public record DemoUser(long id, String fullName, UserRole role) {

    public boolean isHost() {
        return role == UserRole.HOST;
    }
}
