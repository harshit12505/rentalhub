package com.rentalhub.service;

import com.rentalhub.domain.model.User;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.DemoUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The users the demo's "sign in as" switcher offers.
 *
 * There is no login in this project, by design (the spec rules out Spring Security): anyone
 * can act as anyone, which is what makes every feature easy to try. What the switcher needs
 * is the list of users and a way to check that a chosen id is real.
 */
@Service
public class DemoUserService {

    private final UserRepository users;

    public DemoUserService(UserRepository users) {
        this.users = users;
    }

    /** Everyone, hosts first. */
    @Transactional(readOnly = true)
    public List<DemoUser> all() {
        return users.findAllByOrderByRoleDescFullNameAscIdAsc().stream().map(DemoUserService::toDemoUser).toList();
    }

    @Transactional(readOnly = true)
    public Optional<DemoUser> find(long id) {
        return users.findById(id).map(DemoUserService::toDemoUser);
    }

    private static DemoUser toDemoUser(User user) {
        return new DemoUser(user.getId(), user.getFullName(), user.getRole());
    }
}
