package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    /** Everyone, hosts first, for the demo's "sign in as" list. */
    List<User> findAllByOrderByRoleDescFullNameAscIdAsc();
}
