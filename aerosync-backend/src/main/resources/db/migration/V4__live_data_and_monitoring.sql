-- ============================================================================
-- AeroSync Stage 4 - live data ingestion, weather reports and disruption
-- monitoring (PostgreSQL).
--
-- Nothing is dropped, renamed or recreated and no existing row is deleted:
--   * airports gets the ICAO code OpenSky uses for airports;
--   * flight_schedules gets provenance columns (which source wrote the row,
--     when, and whether its times are a plan or an observation);
--   * disruptions (a Hibernate-managed table) gets monitoring columns and a
--     duplicate guard;
--   * live_aircraft_states, weather_reports, ingestion_runs and
--     ingestion_checkpoints are new tables.
-- All new timestamps are TIMESTAMPTZ written as UTC instants, except the
-- disruption timestamps, which stay TIMESTAMP like the existing detected_at
-- and resolved_at columns of that table.
-- ============================================================================

-- ── airports: ICAO code (real public reference data) ────────────────────────
ALTER TABLE airports ADD COLUMN IF NOT EXISTS icao_code VARCHAR(4);
UPDATE airports SET icao_code = CASE iata_code
        WHEN 'BOM' THEN 'VABB' WHEN 'DEL' THEN 'VIDP' WHEN 'BLR' THEN 'VOBL'
        WHEN 'HYD' THEN 'VOHS' WHEN 'CCU' THEN 'VECC' WHEN 'MAA' THEN 'VOMM'
        WHEN 'AMD' THEN 'VAAH' WHEN 'GOI' THEN 'VOGO' WHEN 'PNQ' THEN 'VAPO'
        WHEN 'COK' THEN 'VOCI' WHEN 'JAI' THEN 'VIJP' WHEN 'LKO' THEN 'VILK'
        WHEN 'GAU' THEN 'VEGT' WHEN 'NAG' THEN 'VANP' WHEN 'BBI' THEN 'VEBS'
    END
WHERE icao_code IS NULL
  AND iata_code IN ('BOM', 'DEL', 'BLR', 'HYD', 'CCU', 'MAA', 'AMD', 'GOI', 'PNQ',
                    'COK', 'JAI', 'LKO', 'GAU', 'NAG', 'BBI');
ALTER TABLE airports
    ADD CONSTRAINT uk_airports_icao_code UNIQUE (icao_code);
ALTER TABLE airports
    ADD CONSTRAINT ck_airports_icao_code CHECK (icao_code IS NULL OR icao_code ~ '^[A-Z]{4}$');
COMMENT ON COLUMN airports.icao_code IS 'ICAO location indicator (used by OpenSky); NULL when unknown.';

-- ── flight_schedules: provenance ────────────────────────────────────────────
ALTER TABLE flight_schedules ADD COLUMN IF NOT EXISTS data_source VARCHAR(40);
UPDATE flight_schedules SET data_source = CASE data_origin
        WHEN 'SYNTHETIC' THEN 'SYNTHETIC_GENERATOR'
        WHEN 'MANUAL' THEN 'MANUAL_ENTRY'
        ELSE 'EXTERNAL_IMPORT'
    END
WHERE data_source IS NULL;
ALTER TABLE flight_schedules ALTER COLUMN data_source SET NOT NULL;

ALTER TABLE flight_schedules ADD COLUMN IF NOT EXISTS schedule_basis VARCHAR(20) NOT NULL DEFAULT 'PLANNED';
ALTER TABLE flight_schedules ADD COLUMN IF NOT EXISTS source_record_id VARCHAR(64);
ALTER TABLE flight_schedules ADD COLUMN IF NOT EXISTS callsign VARCHAR(8);
ALTER TABLE flight_schedules ADD COLUMN IF NOT EXISTS aircraft_icao24 VARCHAR(6);
ALTER TABLE flight_schedules ADD COLUMN IF NOT EXISTS ingested_at TIMESTAMPTZ;
ALTER TABLE flight_schedules ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMPTZ;
ALTER TABLE flight_schedules
    ADD CONSTRAINT ck_flight_schedules_basis CHECK (schedule_basis IN ('PLANNED', 'OBSERVED'));
ALTER TABLE flight_schedules
    ADD CONSTRAINT ck_flight_schedules_icao24 CHECK (aircraft_icao24 IS NULL OR aircraft_icao24 ~ '^[0-9a-f]{6}$');
-- One row per external record: re-importing the same record updates it.
CREATE UNIQUE INDEX uk_flight_schedules_source_record
    ON flight_schedules (data_source, source_record_id) WHERE source_record_id IS NOT NULL;
-- External sources such as OpenSky do not report the aircraft type.
ALTER TABLE flight_schedules ALTER COLUMN aircraft_type DROP NOT NULL;
COMMENT ON COLUMN flight_schedules.data_source IS 'Which system wrote the row: SYNTHETIC_GENERATOR, MANUAL_ENTRY, OPENSKY, ...';
COMMENT ON COLUMN flight_schedules.schedule_basis IS 'PLANNED: scheduled_* are a timetable. OBSERVED: no timetable is known; scheduled_* hold the observed departure and arrival.';
COMMENT ON COLUMN flight_schedules.source_record_id IS 'Identifier of the record in the external source (OpenSky: icao24:firstSeen).';
COMMENT ON COLUMN flight_schedules.ingested_at IS 'When ingestion first wrote the row (NULL for rows not written by ingestion).';
COMMENT ON COLUMN flight_schedules.source_updated_at IS 'When ingestion last confirmed or changed the row.';

-- ── live_aircraft_states ────────────────────────────────────────────────────
-- Latest observed state of each aircraft (one row per transponder address).
CREATE TABLE live_aircraft_states (
    icao24                      VARCHAR(6)       NOT NULL,
    callsign                    VARCHAR(8),
    flight_number               VARCHAR(8),
    operating_airline           VARCHAR(3),
    origin_country              VARCHAR(80),
    latitude                    DOUBLE PRECISION NOT NULL,
    longitude                   DOUBLE PRECISION NOT NULL,
    baro_altitude_m             DOUBLE PRECISION,
    geo_altitude_m              DOUBLE PRECISION,
    on_ground                   BOOLEAN          NOT NULL,
    velocity_ms                 DOUBLE PRECISION,
    true_track_deg              DOUBLE PRECISION,
    vertical_rate_ms            DOUBLE PRECISION,
    squawk                      VARCHAR(4),
    position_source             INTEGER,
    time_position               TIMESTAMPTZ,
    last_contact                TIMESTAMPTZ      NOT NULL,
    nearest_airport_id          UUID,
    nearest_airport_distance_km DOUBLE PRECISION,
    data_source                 VARCHAR(40)      NOT NULL,
    snapshot_time               TIMESTAMPTZ      NOT NULL,
    ingested_at                 TIMESTAMPTZ      NOT NULL,
    CONSTRAINT pk_live_aircraft_states PRIMARY KEY (icao24),
    CONSTRAINT fk_live_aircraft_states_airport FOREIGN KEY (nearest_airport_id) REFERENCES airports (airport_id),
    CONSTRAINT ck_live_aircraft_states_icao24 CHECK (icao24 ~ '^[0-9a-f]{6}$'),
    CONSTRAINT ck_live_aircraft_states_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_live_aircraft_states_longitude CHECK (longitude BETWEEN -180 AND 180)
);
CREATE INDEX idx_live_aircraft_states_airport ON live_aircraft_states (nearest_airport_id, last_contact);
CREATE INDEX idx_live_aircraft_states_last_contact ON live_aircraft_states (last_contact);
COMMENT ON TABLE live_aircraft_states IS 'Latest externally observed aircraft state per transponder address. Never generated.';
COMMENT ON COLUMN live_aircraft_states.flight_number IS 'Derived from the callsign (ICAO airline prefix + digits); NULL when the callsign is not a flight number.';
COMMENT ON COLUMN live_aircraft_states.nearest_airport_id IS 'Reference airport within the configured radius of the position; NULL when en route.';

-- ── weather_reports ─────────────────────────────────────────────────────────
CREATE TABLE weather_reports (
    report_id                 UUID             NOT NULL,
    airport_id                UUID             NOT NULL,
    report_kind               VARCHAR(12)      NOT NULL,
    valid_at                  TIMESTAMPTZ      NOT NULL,
    fetched_at                TIMESTAMPTZ      NOT NULL,
    severity_score            INTEGER          NOT NULL,
    condition_code            INTEGER,
    description               VARCHAR(120),
    wind_speed_ms             DOUBLE PRECISION,
    visibility_m              INTEGER,
    temperature_c             DOUBLE PRECISION,
    precipitation_mm          DOUBLE PRECISION,
    precipitation_probability DOUBLE PRECISION,
    data_source               VARCHAR(40)      NOT NULL,
    CONSTRAINT pk_weather_reports PRIMARY KEY (report_id),
    CONSTRAINT uk_weather_reports_report UNIQUE (airport_id, report_kind, valid_at, data_source),
    CONSTRAINT fk_weather_reports_airport FOREIGN KEY (airport_id) REFERENCES airports (airport_id),
    CONSTRAINT ck_weather_reports_kind CHECK (report_kind IN ('OBSERVATION', 'FORECAST')),
    CONSTRAINT ck_weather_reports_score CHECK (severity_score BETWEEN 0 AND 3)
);
CREATE INDEX idx_weather_reports_latest ON weather_reports (airport_id, report_kind, valid_at DESC);
COMMENT ON COLUMN weather_reports.report_kind IS 'OBSERVATION: measured conditions at valid_at. FORECAST: predicted conditions for valid_at.';
COMMENT ON COLUMN weather_reports.valid_at IS 'Observation time, or the time a forecast applies to.';

-- ── ingestion_runs / ingestion_checkpoints ──────────────────────────────────
CREATE TABLE ingestion_runs (
    run_id               UUID        NOT NULL,
    data_source          VARCHAR(40) NOT NULL,
    feed                 VARCHAR(40) NOT NULL,
    started_at           TIMESTAMPTZ NOT NULL,
    finished_at          TIMESTAMPTZ,
    status               VARCHAR(20) NOT NULL,
    records_fetched      INTEGER     NOT NULL DEFAULT 0,
    records_inserted     INTEGER     NOT NULL DEFAULT 0,
    records_updated      INTEGER     NOT NULL DEFAULT 0,
    records_unchanged    INTEGER     NOT NULL DEFAULT 0,
    records_skipped      INTEGER     NOT NULL DEFAULT 0,
    api_calls            INTEGER     NOT NULL DEFAULT 0,
    rate_limit_remaining INTEGER,
    details              VARCHAR(1000),
    error_message        VARCHAR(500),
    CONSTRAINT pk_ingestion_runs PRIMARY KEY (run_id),
    CONSTRAINT ck_ingestion_runs_status CHECK (status IN
        ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'RATE_LIMITED', 'SKIPPED'))
);
CREATE INDEX idx_ingestion_runs_feed ON ingestion_runs (data_source, feed, started_at DESC);
COMMENT ON TABLE ingestion_runs IS 'One row per ingestion attempt: what was fetched and what changed. Never holds credentials.';

CREATE TABLE ingestion_checkpoints (
    checkpoint_id UUID        NOT NULL,
    data_source   VARCHAR(40) NOT NULL,
    feed          VARCHAR(40) NOT NULL,
    scope_key     VARCHAR(40) NOT NULL,
    period_start  DATE        NOT NULL,
    status        VARCHAR(20) NOT NULL,
    attempts      INTEGER     NOT NULL DEFAULT 0,
    records       INTEGER     NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_ingestion_checkpoints PRIMARY KEY (checkpoint_id),
    CONSTRAINT uk_ingestion_checkpoints_period UNIQUE (data_source, feed, scope_key, period_start),
    CONSTRAINT ck_ingestion_checkpoints_status CHECK (status IN ('COMPLETE', 'EMPTY', 'PENDING'))
);
COMMENT ON TABLE ingestion_checkpoints IS 'Which periods of a batch feed have been imported, so they are not fetched again.';

-- ── disruptions (Hibernate-managed table) ───────────────────────────────────
-- On a brand-new database Flyway runs before Hibernate creates the legacy
-- tables, so create disruptions here with the columns Hibernate maps for
-- com.aerosync.model.Disruption (same approach as gates in V1). On existing
-- databases this statement is a no-op and the ALTERs below add the new columns.
CREATE TABLE IF NOT EXISTS disruptions (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    flight_id           VARCHAR(255),
    type                VARCHAR(255),
    status              VARCHAR(255),
    description         VARCHAR(255),
    delay_minutes       INTEGER,
    passengers_affected INTEGER,
    cost_impact         DOUBLE PRECISION,
    resolution_json     JSONB,
    resolution_time_ms  BIGINT,
    detected_at         TIMESTAMP(6),
    resolved_at         TIMESTAMP(6)
);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS severity VARCHAR(20);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION;
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS detection_source VARCHAR(40);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS detection_rule VARCHAR(40);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS detection_key VARCHAR(160);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS data_origin VARCHAR(20);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS origin_airport VARCHAR(3);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS destination_airport VARCHAR(3);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS flight_instance_id UUID;
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS last_observed_at TIMESTAMP(6);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS proposed_at TIMESTAMP(6);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP(6);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS approved_by VARCHAR(64);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMP(6);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS rejected_by VARCHAR(64);
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS rejection_count INTEGER;
ALTER TABLE disruptions ADD COLUMN IF NOT EXISTS resolution_note VARCHAR(255);

-- Stage 4 adds disruption types. Hibernate created a CHECK constraint listing
-- the old enum values and does not update it, so drop any CHECK constraint on
-- disruptions.type and replace it with a named one that knows every type.
DO $$
DECLARE
    old_check record;
BEGIN
    FOR old_check IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
        WHERE con.conrelid = 'disruptions'::regclass
          AND con.contype = 'c'
          AND att.attname = 'type'
    LOOP
        EXECUTE format('ALTER TABLE disruptions DROP CONSTRAINT %I', old_check.conname);
    END LOOP;
END
$$;
ALTER TABLE disruptions
    ADD CONSTRAINT ck_disruptions_type CHECK (type IS NULL OR type IN (
        'WEATHER_DELAY', 'CREW_TIMEOUT', 'AIRCRAFT_FAULT', 'GATE_CONFLICT', 'CASCADING_DELAY',
        'FLIGHT_DELAY', 'TURNAROUND_CONFLICT', 'MISSED_CONNECTION', 'AIRCRAFT_UNAVAILABLE'));
ALTER TABLE disruptions
    ADD CONSTRAINT ck_disruptions_severity CHECK (severity IS NULL OR severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));
ALTER TABLE disruptions
    ADD CONSTRAINT ck_disruptions_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1));

-- At most one open disruption per detected event (detection_key identifies the
-- event; manually reported disruptions have no key and are not limited).
CREATE UNIQUE INDEX uk_disruptions_open_detection
    ON disruptions (detection_key)
    WHERE detection_key IS NOT NULL AND status IN ('DETECTED', 'PROPOSED', 'RESOLVING', 'FAILED');
CREATE INDEX idx_disruptions_origin_airport ON disruptions (origin_airport);
CREATE INDEX idx_disruptions_flight_instance ON disruptions (flight_instance_id);
COMMENT ON COLUMN disruptions.detection_source IS 'MANUAL, SIMULATION or MONITOR.';
COMMENT ON COLUMN disruptions.detection_rule IS 'Monitoring rule that detected the event, for example WEATHER_OBSERVED.';
COMMENT ON COLUMN disruptions.detection_key IS 'Identifies the detected event; used to avoid duplicate open disruptions.';
COMMENT ON COLUMN disruptions.flight_instance_id IS 'Set for disruptions of operational-network flights (flight_instances); these are advisory.';
