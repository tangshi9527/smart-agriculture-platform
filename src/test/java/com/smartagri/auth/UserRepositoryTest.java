package com.smartagri.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Test
    void saveAndFindByUsername() {
        User u = new User();
        u.setUsername("testuser");
        u.setPassword("$2a$enc");
        u.setRole("ADMIN");
        userRepository.save(u);

        User found = userRepository.findByUsername("testuser").orElse(null);
        assertNotNull(found);
        assertEquals("ADMIN", found.getRole());
    }

    @Test
    void uniqueUsernameEnforced() {
        User u1 = new User();
        u1.setUsername("dup");
        u1.setPassword("x");
        User u2 = new User();
        u2.setUsername("dup");
        u2.setPassword("y");
        userRepository.save(u1);
        assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(u2));
    }
}
