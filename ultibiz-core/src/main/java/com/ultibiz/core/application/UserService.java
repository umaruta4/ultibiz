package com.ultibiz.core.application;

import com.ultibiz.core.infrastructure.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Use cases around application users.
 */
@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public long count() {
        return users.count();
    }
}
