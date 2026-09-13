package com.pulse.control;

import com.pulse.entity.PasswordUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;

import java.util.Map;

/**
 * Application-scoped Pbkdf2PasswordHash provider backed by PBKDF2WithHmacSHA256.
 * Guarantees CDI dependency satisfaction across WildFly container and test environments.
 */
@ApplicationScoped
public class DefaultPbkdf2PasswordHash implements Pbkdf2PasswordHash {

    @Override
    public void initialize(Map<String, String> parameters) {
    }

    @Override
    public String generate(char[] password) {
        if (password == null) {
            return null;
        }
        return PasswordUtil.hash(new String(password));
    }

    @Override
    public boolean verify(char[] password, String hashedPassword) {
        if (password == null || hashedPassword == null) {
            return false;
        }
        return PasswordUtil.verify(new String(password), hashedPassword);
    }
}
