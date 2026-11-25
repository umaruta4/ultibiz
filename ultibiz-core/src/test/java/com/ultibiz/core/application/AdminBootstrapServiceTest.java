package com.ultibiz.core.application;

import com.ultibiz.core.domain.AppUser;
import com.ultibiz.core.infrastructure.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void lockedAdminGetsFreshBcryptHashFromConfiguredPassword() {
        AppUser locked = new AppUser(1L, 1L, "admin", AppUser.LOCKED_HASH,
                "Administrator", "admin@ultibiz.local", "id", null, true);
        when(users.findByUsername("admin")).thenReturn(Optional.of(locked));

        AdminBootstrapService bootstrap =
                new AdminBootstrapService(users, encoder, "S3cret!Password");
        bootstrap.onApplicationReady();

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(users).updatePasswordHash(eq(1L), hashCaptor.capture());
        String storedHash = hashCaptor.getValue();

        assertNotEquals(AppUser.LOCKED_HASH, storedHash);
        assertTrue(storedHash.startsWith("$2"), "expected bcrypt hash, got: " + storedHash);
        assertTrue(encoder.matches("S3cret!Password", storedHash));
    }

    @Test
    void unlockedAdminIsNotTouched() {
        String existingHash = encoder.encode("keepme");
        AppUser active = new AppUser(1L, 1L, "admin", existingHash,
                "Administrator", "admin@ultibiz.local", "id", null, true);
        when(users.findByUsername("admin")).thenReturn(Optional.of(active));

        AdminBootstrapService bootstrap =
                new AdminBootstrapService(users, encoder, "different");
        bootstrap.onApplicationReady();

        verify(users, org.mockito.Mockito.never())
                .updatePasswordHash(eq(1L), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void generatedPasswordIsSixteenCharsFromSafeAlphabet() {
        String p1 = AdminBootstrapService.generatePassword();
        String p2 = AdminBootstrapService.generatePassword();

        assertEquals(16, p1.length());
        assertEquals(16, p2.length());
        assertNotEquals(p1, p2);
        assertTrue(p1.chars().allMatch(c -> Character.isLetterOrDigit(c)));
    }
}
