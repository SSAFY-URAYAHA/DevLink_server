package com.devlink.server;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class DevLinkServerIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TestRestTemplate rest;
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void migratedSchemaPassesValidationAndHealthAndSecurityWork() {
        assertThat(entityManagerFactory.getMetamodel().getEntities()).hasSize(7);
        assertThat(flyway.info().all()).hasSize(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tables).containsExactlyInAnyOrder("users", "teams", "team_members", "agents",
                "tasks", "task_files", "task_events", "flyway_schema_history");

        var health = rest.getForEntity("/api/v1/health", JsonNode.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().path("success").asBoolean()).isTrue();
        assertThat(health.getBody().path("data").asText()).isEqualTo("UP");

        var teams = rest.getForEntity("/api/v1/teams", JsonNode.class);
        assertThat(teams.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(teams.getHeaders().getLocation()).isNull();
        assertThat(teams.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(teams.getBody().path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");

        var csrf = rest.postForEntity("/api/v1/auth/login", null, JsonNode.class);
        assertThat(csrf.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(csrf.getBody().path("code").asText()).isEqualTo("ACCESS_DENIED");
    }
}
