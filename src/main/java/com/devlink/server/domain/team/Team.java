package com.devlink.server.domain.team;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: identity.
@Entity
@Table(name = "teams", uniqueConstraints = @UniqueConstraint(name = "uk_teams_invite_code", columnNames = {"invite_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", length = 255, nullable = false)
    private Long id;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "invite_code", length = 64, nullable = false)
    private String inviteCode;

    @Column(name = "created_at", length = 255, nullable = false)
    private LocalDateTime createdAt;

    public static Team create(String name, String inviteCode) {
        requireValue(name, "name");
        requireValue(inviteCode, "inviteCode");
        Team entity = new Team();
        entity.name = name;
        entity.inviteCode = inviteCode;
        return entity;
    }

    public void regenerateInviteCode(String code) {
        requireValue(code, "inviteCode");
        this.inviteCode = code;
    }

    @PrePersist
    private void initializeTimestamp() {
        this.createdAt = LocalDateTime.now();
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
