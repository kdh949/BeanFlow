#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
image="$(awk '/^FROM nginxinc\/nginx-unprivileged:/ {print $2}' "$root/frontend/Dockerfile")"
fixture="$(mktemp -d)"
prefix="beanflow-demo-entry-$$"
cleanup() {
  docker rm -f "$prefix-web" "$prefix-api" >/dev/null 2>&1 || true
  docker network rm "$prefix" >/dev/null 2>&1 || true
  rm -rf "$fixture"
}
trap cleanup EXIT
mkdir -p "$fixture/html/demo/catalog"
printf '<!doctype html><title>BeanFlow route test</title>' > "$fixture/html/index.html"
cp "$root/frontend/public/demo/catalog/americano.webp" "$fixture/html/demo/catalog/americano.webp"
printf 'server { listen 8080; location / { return 418; } }' > "$fixture/upstream.conf"
docker network create --internal "$prefix" >/dev/null
docker run -d --name "$prefix-api" --network "$prefix" --network-alias api --network-alias keycloak \
  -v "$fixture/upstream.conf:/etc/nginx/conf.d/default.conf:ro" "$image" >/dev/null
for config in frontend/nginx/default.conf deploy/nginx/external-keycloak.conf; do
  docker run -d --name "$prefix-web" --network "$prefix" \
    -v "$root/$config:/etc/nginx/conf.d/default.conf:ro" \
    -v "$fixture/html:/usr/share/nginx/html:ro" "$image" >/dev/null
  # Use the container's loopback, leaving no host listening ports.
  ready=false
  for _ in $(seq 1 30); do
    if docker exec "$prefix-web" wget -q -O /dev/null http://127.0.0.1:8080/healthz; then ready=true; break; fi
    sleep 0.2
  done
  [[ "$ready" == true ]] || { docker logs "$prefix-web"; exit 1; }
  for route in /demo /demo/; do
    if ! docker exec "$prefix-web" wget -S -O - "http://127.0.0.1:8080$route" > "$fixture/body" 2> "$fixture/headers"; then
      cat "$fixture/headers" >&2; exit 1
    fi
    cmp "$fixture/body" "$fixture/html/index.html"
    grep -q 'HTTP/1.1 200' "$fixture/headers"
    ! grep -q 'HTTP/1.1 301' "$fixture/headers"
    grep -qi 'Cache-Control: no-store' "$fixture/headers"
  done
  docker exec "$prefix-web" wget -q -O - http://127.0.0.1:8080/demo/catalog/americano.webp > "$fixture/image"
  cmp "$fixture/image" "$fixture/html/demo/catalog/americano.webp"
  for spec in '/assets/missing.js 404' '/auth/admin/ 404' '/api/route-probe 418'; do
    read -r route status <<< "$spec"
    if docker exec "$prefix-web" wget -S -O - "http://127.0.0.1:8080$route" > "$fixture/body" 2> "$fixture/headers"; then
      echo "Unexpected success for $route ($config)" >&2; exit 1
    fi
    grep -q "HTTP/1.1 $status" "$fixture/headers"
  done
  docker rm -f "$prefix-web" >/dev/null
  echo "Demo entry runtime contract passed: $config"
done
