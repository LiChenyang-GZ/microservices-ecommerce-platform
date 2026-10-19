#!/bin/sh
# Database per service (ADR-12): one PostgreSQL instance, one database and one
# login per service. Each login can only connect to its own database.
#
# The postgres image runs this only when the data volume is empty (first start).
# After changing a password or adding a service here, recreate the volume:
#   docker compose -f backend/compose.yml down -v
set -eu

for svc in store bank delivery email tracking; do
  # e.g. BANK_DB_PASSWORD, passed to this container by compose.yml
  pw_var="$(echo "$svc" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
  eval "pw=\${$pw_var:?$pw_var is not set}"

  # -v passes the values as psql variables; :'pw' quotes them safely as a literal.
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
       -v svc_user="${svc}_user" -v svc_db="${svc}_db" -v pw="$pw" <<'SQL'
CREATE ROLE :"svc_user" LOGIN PASSWORD :'pw';
CREATE DATABASE :"svc_db" OWNER :"svc_user";
-- By default every login may connect to every database. Take that away,
-- so only the owner (and the superuser) can connect.
REVOKE CONNECT, TEMPORARY ON DATABASE :"svc_db" FROM PUBLIC;
SQL
  echo "created ${svc}_db owned by ${svc}_user"
done
