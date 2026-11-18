#!/bin/bash
# Runs once, when the data volume is created. Same split as the AWS bootstrap:
# handwash_migrator owns the schema (Flyway); handwash_runtime only reads and writes rows.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v runtime_pw="$HANDWASH_RUNTIME_PASSWORD" -v migrator_pw="$HANDWASH_MIGRATOR_PASSWORD" <<'SQL'
CREATE ROLE handwash_runtime LOGIN PASSWORD :'runtime_pw';
CREATE ROLE handwash_migrator LOGIN PASSWORD :'migrator_pw';
GRANT CONNECT ON DATABASE handwash TO handwash_runtime, handwash_migrator;
ALTER SCHEMA public OWNER TO handwash_migrator;
GRANT USAGE ON SCHEMA public TO handwash_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE handwash_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO handwash_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE handwash_migrator IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO handwash_runtime;
SQL
