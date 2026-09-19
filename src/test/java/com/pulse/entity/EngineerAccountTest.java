package com.pulse.entity;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class EngineerAccountTest {

    @Test
    @DisplayName("EngineRole enum should have values: ENGINEER, OPERATOR, and ADMIN")
    void testEngineRoleValues() {
        assertThat(EngineRole.values()).containsExactlyInAnyOrder(EngineRole.ENGINEER, EngineRole.OPERATOR, EngineRole.ADMIN);
    }

    @Test
    @DisplayName("EngineerAccount should be annotated with @Entity, @Table(name='engineer_account'), @Cacheable(false)")
    void testEntityAnnotations() {
        Entity entityAnnotation = EngineerAccount.class.getAnnotation(Entity.class);
        assertThat(entityAnnotation).isNotNull();

        Table tableAnnotation = EngineerAccount.class.getAnnotation(Table.class);
        assertThat(tableAnnotation).isNotNull();
        assertThat(tableAnnotation.name()).isEqualTo("engineer_account");

        Cacheable cacheableAnnotation = EngineerAccount.class.getAnnotation(Cacheable.class);
        assertThat(cacheableAnnotation).isNotNull();
        assertThat(cacheableAnnotation.value()).isFalse();
    }

    @Test
    @DisplayName("username field should have @Column(nullable = false, unique = true, length = 100)")
    void testUsernameFieldConstraints() throws NoSuchFieldException {
        Field usernameField = EngineerAccount.class.getDeclaredField("username");
        Column column = usernameField.getAnnotation(Column.class);

        assertThat(column).isNotNull();
        assertThat(column.nullable()).isFalse();
        assertThat(column.unique()).isTrue();
        assertThat(column.length()).isEqualTo(100);
    }

    @Test
    @DisplayName("version field should be annotated with @Version and type Long")
    void testVersionField() throws NoSuchFieldException {
        Field versionField = EngineerAccount.class.getDeclaredField("version");
        Version versionAnnotation = versionField.getAnnotation(Version.class);

        assertThat(versionAnnotation).isNotNull();
        assertThat(versionField.getType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("id field should be annotated with @Id")
    void testIdField() throws NoSuchFieldException {
        Field idField = EngineerAccount.class.getDeclaredField("id");
        Id idAnnotation = idField.getAnnotation(Id.class);

        assertThat(idAnnotation).isNotNull();
    }

    @Test
    @DisplayName("verifyPassword should return true for matching password and false for wrong password")
    void testPasswordVerification() {
        String rawPassword = "securePassword123!";
        String hash = PasswordUtil.hash(rawPassword);

        EngineerAccount account = new EngineerAccount("john_doe", hash, EngineRole.ENGINEER);

        assertThat(account.verifyPassword(rawPassword)).isTrue();
        assertThat(account.verifyPassword("wrongPassword")).isFalse();
        assertThat(account.verifyPassword(null)).isFalse();
    }
}
