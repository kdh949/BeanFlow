#!/bin/sh
set -eu

source_file=/run/secrets/BEANFLOW_POSTGRES_PASSWORD
staged_file=/run/beanflow-postgres-exporter/password

if [ "$(id -u)" != 0 ]; then
  echo 'PostgreSQL exporter secret staging requires startup UID 0.' >&2
  exit 1
fi
if [ ! -f "$source_file" ] || [ ! -r "$source_file" ] || [ ! -s "$source_file" ]; then
  echo 'PostgreSQL exporter requires a readable, non-empty DB password file.' >&2
  exit 1
fi
if [ "${DATA_SOURCE_PASS_FILE:-}" != "$staged_file" ]; then
  echo 'PostgreSQL exporter password must use the private staged file.' >&2
  exit 1
fi

umask 077
install -m 0400 -o 65534 -g 65534 "$source_file" "$staged_file"
exec su -s /bin/sh nobody -c 'exec /bin/postgres_exporter "$@"' -- postgres-exporter "$@"
