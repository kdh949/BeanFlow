#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly image="$(awk '$1 == "FROM" && $2 ~ /^nginxinc\/nginx-unprivileged:/ {print $2}' "$root/frontend/Dockerfile")"
readonly runtime_dir="$(mktemp -d)"
readonly network="beanflow-external-keycloak-test-$$"
api_id=""
web_id=""
network_created=false
cleanup() {
  [[ -z "$web_id" ]] || docker rm -f "$web_id" >/dev/null
  [[ -z "$api_id" ]] || docker rm -f "$api_id" >/dev/null
  [[ "$network_created" == false ]] || docker network rm "$network" >/dev/null
  rm -rf "$runtime_dir"
}
trap cleanup EXIT

docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"
docker network create "$network" >/dev/null
network_created=true
mkdir "$runtime_dir/html"
printf 'beanflow-external-keycloak-spa' > "$runtime_dir/html/index.html"
cat > "$runtime_dir/api.conf" <<'NGINX'
server {
    listen 8080;
    location /api/ {
        default_type application/json;
        return 200 '{"test":"external-keycloak-api"}';
    }
}
NGINX
api_id="$(docker run -d --network "$network" --network-alias api --read-only --tmpfs /tmp \
  -v "$runtime_dir/api.conf:/etc/nginx/conf.d/default.conf:ro" "$image")"
docker run --rm --network "$network" --read-only --tmpfs /tmp \
  -v "$root/deploy/nginx/external-keycloak.conf:/etc/nginx/conf.d/default.conf:ro" \
  "$image" nginx -t
web_id="$(docker run -d --network "$network" --read-only --tmpfs /tmp -p 127.0.0.1::8080 \
  -v "$root/deploy/nginx/external-keycloak.conf:/etc/nginx/conf.d/default.conf:ro" \
  -v "$runtime_dir/html:/usr/share/nginx/html:ro" "$image")"
readonly address="$(docker port "$web_id" 8080/tcp)"
ready=false
for attempt in {1..30}; do
  if curl --fail --silent --max-time 1 "http://$address/healthz" >/dev/null; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == true ]] || { docker logs "$web_id"; exit 1; }
[[ "$(curl --fail --silent --show-error --max-time 5 "http://$address/ops/auth/callback")" == beanflow-external-keycloak-spa ]]
[[ "$(curl --fail --silent --show-error --max-time 5 "http://$address/api/v1/auth/operations/config")" == '{"test":"external-keycloak-api"}' ]]
for path in /auth /auth/admin/ /auth/realms/beanflow /auth/resources/test; do
  [[ "$(curl --silent --show-error --max-time 5 --output /dev/null --write-out '%{http_code}' "http://$address$path")" == 404 ]]
done
curl --fail --silent --show-error --max-time 5 "http://$address/ops/auth/callback?code=never-log-this-code" >/dev/null
if docker logs "$web_id" 2>&1 | grep -q never-log-this-code; then
  echo "External Nginx access log exposed callback query parameters" >&2
  exit 1
fi
echo "External Keycloak Nginx syntax, health, SPA, API proxy, auth isolation and safe logging passed."
