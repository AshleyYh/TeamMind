-- Run once against a new database:
-- psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f infra/db/init.sql
-- Normal application startup uses Flyway and records this migration in flyway_schema_history.
\echo 'Initializing TeamMind Phase 1 schema...'
\ir ../../team-mind-web/src/main/resources/db/migration/V1__create_phase1_schema.sql
\echo 'TeamMind Phase 1 schema initialized.'
