#!/bin/sh
# Database per service (ADR-12), for Kubernetes. Run by the db-init Job.
#
# Same result as backend/docker/postgres/init/01-create-databases.sh, but safe to
# run again: compose runs its script once on an empty volume, while a Job can be
# retried, re-run by hand, or run against a database that already exists.
#   - a login or database that already exists is left alone;
#   - the password is always set again, so changing it in backend/.env and
#     re-running the Job is enough (compose needs `down -v` for that).
set -eu

for svc in store bank delivery email tracking; do
  pw_var="$(echo "$svc" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
  eval "pw=\${$pw_var:?$pw_var is not set}"

  # \gexec runs each row of the SELECT as a statement; no row, nothing runs.
  # format(%I, %L) quotes the name and the password safely.
  psql -v ON_ERROR_STOP=1 -d postgres \
       -v svc_user="${svc}_user" -v svc_db="${svc}_db" -v pw="$pw" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'svc_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'svc_user') \gexec
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'svc_user', :'pw') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'svc_db', :'svc_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'svc_db') \gexec
-- Only the owner (and the superuser) may connect.
REVOKE CONNECT, TEMPORARY ON DATABASE :"svc_db" FROM PUBLIC;
SQL
  echo "ready: ${svc}_db owned by ${svc}_user"
done
