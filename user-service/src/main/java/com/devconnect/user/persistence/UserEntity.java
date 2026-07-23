package com.devconnect.user.persistence;

import com.devconnect.user.support.EmailNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
public class UserEntity {

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "email", length = 254)
    private String email;

    protected UserEntity() {
    }

    public UserEntity(String userId, String status) {
        this(userId, status, null);
    }

    public UserEntity(String userId, String status, String email) {
        this.userId = userId;
        this.status = status;
        this.email = EmailNormalizer.normalize(email);
    }

    public String getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public String getEmail() {
        return email;
    }

    public void updateStatus(String status) {
        this.status = status;
    }

    public void updateEmail(String email) {
        this.email = EmailNormalizer.normalize(email);
    }
}
