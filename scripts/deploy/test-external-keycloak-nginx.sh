#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly image="$(awk '$1 == "FROM" && $2 ~ /^nginxinc\/nginx-unprivileged:/ {print $2}' "$root/frontend/Dockerfile")"
readonly runtime_dir="$(mktemp -d)"
readonly network="beanflow-external-keycloak-test-$$"
api_id=""
media_id=""
web_id=""
network_created=false
cleanup() {
  [[ -z "$web_id" ]] || docker rm -f "$web_id" >/dev/null
  [[ -z "$api_id" ]] || docker rm -f "$api_id" >/dev/null
  [[ -z "$media_id" ]] || docker rm -f "$media_id" >/dev/null
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
cat > "$runtime_dir/media.conf" <<'NGINX'
# A request-contract fixture, not a substitute for AIStor SigV4 validation.
server {
    listen 9000;
    location = /healthz { return 200 'ok'; }
    location / {
        if ($http_host != "images.example.test:5443") { return 400; }
        if ($http_cookie != "") { return 400; }
        if ($http_authorization != "") { return 400; }
        if ($args != "X-Amz-Signature=fixture-signature-never-log&X-Amz-Credential=fixture%2Fscope&x=a%2Bb") { return 403; }
        if ($uri ~ /missing.jpg$) { return 404; }
        if ($uri ~ /unavailable.jpg$) { return 503; }
        default_type image/jpeg;
        add_header X-Test-Original-URI $request_uri;
        add_header Cache-Control "public, max-age=3600";
        return 200 'image-fixture';
    }
}
NGINX
cat > "$runtime_dir/deployment.env" <<'ENV'
BEANFLOW_PUBLIC_ORIGIN=https://images.example.test:5443
BEANFLOW_AISTOR_PUBLIC_ENDPOINT=https://images.example.test:5443
BEANFLOW_AISTOR_ENDPOINT=http://aistor:9000
BEANFLOW_AISTOR_BUCKET=beanflow-test
ENV
python3 "$root/scripts/deploy/render_external_nginx.py" --env-file "$runtime_dir/deployment.env" > "$runtime_dir/web.conf"
# Even with no AIStor DNS record yet, Nginx must start and pass its syntax check.
docker run --rm --network "$network" --read-only --tmpfs /tmp \
  -v "$runtime_dir/web.conf:/etc/nginx/conf.d/default.conf:ro" \
  "$image" nginx -t
web_id="$(docker run -d --network "$network" --read-only --tmpfs /tmp -p 127.0.0.1::8080 \
  -v "$runtime_dir/web.conf:/etc/nginx/conf.d/default.conf:ro" \
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
media_id="$(docker run -d --network "$network" --network-alias aistor --read-only --tmpfs /tmp \
  -v "$runtime_dir/media.conf:/etc/nginx/conf.d/default.conf:ro" "$image")"
ready=false
for attempt in {1..30}; do
  if docker exec "$media_id" wget -q -O /dev/null http://127.0.0.1:9000/healthz; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == true ]] || { docker logs "$media_id"; exit 1; }
readonly query='X-Amz-Signature=fixture-signature-never-log&X-Amz-Credential=fixture%2Fscope&x=a%2Bb'
for prefix in stores menus campaigns; do
  path="/beanflow-test/$prefix/private-object%20name.jpg"
  curl --fail --silent --show-error --max-time 5 -D "$runtime_dir/headers" -o "$runtime_dir/body" \
    -H 'Cookie: application-session=never-forward' -H 'Authorization: Bearer never-forward' \
    "http://$address$path?$query"
  [[ "$(cat "$runtime_dir/body")" == image-fixture ]]
  tr -d '\r' < "$runtime_dir/headers" > "$runtime_dir/clean-headers"
  grep -Fqi "X-Test-Original-URI: $path?$query" "$runtime_dir/clean-headers"
  grep -qi '^Content-Type: image/jpeg$' "$runtime_dir/clean-headers"
  grep -qi '^Cache-Control: private, no-store$' "$runtime_dir/clean-headers"
  [[ "$(grep -ci '^Cache-Control:' "$runtime_dir/clean-headers")" == 1 ]]
  [[ "$(curl --silent --show-error --head --max-time 5 -o /dev/null -w '%{http_code}' "http://$address$path?$query")" == 200 ]]
  [[ "$(curl --silent --show-error --max-time 5 -o /dev/null -w '%{http_code}' "http://$address$path")" == 403 ]]
  for method in PUT POST PATCH DELETE OPTIONS; do
    [[ "$(curl --silent --show-error --max-time 5 -X "$method" -o /dev/null -w '%{http_code}' "http://$address$path?$query")" == 403 ]]
  done
done
for path in /beanflow-test /beanflow-test/ /beanflow-test/other/private-object.jpg; do
  [[ "$(curl --silent --show-error --max-time 5 -o /dev/null -w '%{http_code}' "http://$address$path?$query")" == 404 ]]
done
for result in missing:404 unavailable:503; do
  [[ "$(curl --silent --show-error --max-time 5 -o /dev/null -w '%{http_code}' "http://$address/beanflow-test/stores/${result%:*}.jpg?$query")" == "${result#*:}" ]]
done
docker stop "$media_id" >/dev/null
failure_status="$(curl --silent --show-error --max-time 8 -o /dev/null -w '%{http_code}' "http://$address/beanflow-test/stores/private-object.jpg?$query")"
# A stopped container can refuse connections or time out while its IP is still cached.
[[ "$failure_status" == 502 || "$failure_status" == 504 ]]
[[ "$(curl --fail --silent --show-error --max-time 5 "http://$address/healthz")" == ok ]]
[[ "$(curl --fail --silent --show-error --max-time 5 "http://$address/api/v1/auth/operations/config")" == '{"test":"external-keycloak-api"}' ]]
curl --fail --silent --show-error --max-time 5 "http://$address/ops/auth/callback?code=never-log-this-code" >/dev/null
if docker logs "$web_id" 2>&1 | grep -Eq 'never-log-this-code|fixture-signature-never-log|private-object|never-forward'; then
  echo "External Nginx log exposed callback/media credentials or object paths" >&2
  exit 1
fi
echo "External Nginx syntax, health, SPA/API, auth isolation, media URI/Host, read-only methods, upstream failures and safe logging passed."
