package com.pulse.entity;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;

import java.time.Instant;
import java.util.Objects;

/**
 * Entity representing an engineer or administrator account in helix-cortex.
 * Security-sensitive, never cached in the JPA L2 cache.
 */
@Entity
@Table(name = "engineer_account")
@Cacheable(false)
public class EngineerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EngineRole role;

    @Version
    private Long version;

    @Column(name = "created_at")
    private Instant createdAt;

    public EngineerAccount() {
        this.createdAt = Instant.now();
    }

    public EngineerAccount(String username, String passwordHash, EngineRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    /**
     * Verifies raw candidate password against the stored PBKDF2 hash using
     * container Pbkdf2PasswordHash or standard PBKDF2 fallback outside container.
     *
     * @param raw the raw unhashed password
     * @return true if password matches, false otherwise
     */
    public boolean verifyPassword(String raw) {
        if (raw == null || this.passwordHash == null) {
            return false;
        }
        try {
            Pbkdf2PasswordHash hasher = CDI.current().select(Pbkdf2PasswordHash.class).get();
            return hasher.verify(raw.toCharArray(), this.passwordHash);
        } catch (Exception e) {
            return PasswordUtil.verify(raw, this.passwordHash);
        }
    }

    public boolean verifyPassword(String raw, Pbkdf2PasswordHash hasher) {
        if (raw == null || this.passwordHash == null || hasher == null) {
            return false;
        }
        return hasher.verify(raw.toCharArray(), this.passwordHash);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public EngineRole getRole() {
        return role;
    }

    public void setRole(EngineRole role) {
        this.role = role;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EngineerAccount that = (EngineerAccount) o;
        return Objects.equals(id, that.id) && Objects.equals(username, that.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, username);
    }

    @Override
    public String toString() {
        return "EngineerAccount{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", role=" + role +
                ", version=" + version +
                ", createdAt=" + createdAt +
                '}';
    }
}
