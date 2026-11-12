package com.aerosync.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real Flyway migrations against a real PostgreSQL (the other tests
 * use H2 with a schema generated from the entities, so they do not execute
 * the migration SQL).
 *
 * <p>Enabled only when {@code AEROSYNC_TEST_POSTGRES_URL} is set, for example
 * in CI where a PostgreSQL service container is available:</p>
 * <pre>
 * AEROSYNC_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/aerosync_test
 * AEROSYNC_TEST_POSTGRES_USER=postgres
 * AEROSYNC_TEST_POSTGRES_PASSWORD=...
 * </pre>
 * Each run works in its own schema and drops it afterwards.
 */
@EnabledIfEnvironmentVariable(named = "AEROSYNC_TEST_POSTGRES_URL", matches = ".+")
class FlywayMigrationPostgresTest {

    private static final String URL = System.getenv("AEROSYNC_TEST_POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("AEROSYNC_TEST_POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("AEROSYNC_TEST_POSTGRES_PASSWORD", "");

    @Test
    void migrationsApplyToAnEmptySchemaValidateAndAreNotAppliedTwice() throws Exception {
        String schema = "aerosync_migration_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Flyway flyway = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
        try {
            MigrateResult first = flyway.migrate();
            assertTrue(first.success);
            assertEquals(4, first.migrationsExecuted, "V1 to V4");
            assertEquals("4", first.targetSchemaVersion);

            flyway.validate();
            assertEquals(0, flyway.migrate().migrationsExecuted, "a second run must find nothing to do");

            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                assertEquals(15, count(statement, "SELECT count(*) FROM airports WHERE icao_code IS NOT NULL"));
                assertEquals(0, count(statement, "SELECT count(*) FROM app_users"));
                // Stage 4 disruption types are accepted, unknown ones are not.
                statement.execute("INSERT INTO disruptions (flight_id, status, type, detection_key) "
                        + "VALUES ('AI-1', 'DETECTED', 'TURNAROUND_CONFLICT', 'k')");
                assertThrows(Exception.class, () -> statement.execute(
                        "INSERT INTO disruptions (flight_id, status, type) VALUES ('AI-1', 'DETECTED', 'BOGUS')"));
                // One open disruption per detected event.
                assertThrows(Exception.class, () -> statement.execute(
                        "INSERT INTO disruptions (flight_id, status, type, detection_key) "
                                + "VALUES ('AI-1', 'PROPOSED', 'TURNAROUND_CONFLICT', 'k')"));
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static int count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
