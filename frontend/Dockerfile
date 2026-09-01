FROM node:24.19.0-alpine3.24 AS build

WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci
COPY openapi ./openapi
COPY frontend ./frontend
RUN cd frontend \
    && npm run build \
    && test -f dist/client/index.html

FROM nginxinc/nginx-unprivileged:1.30.4-alpine3.24

COPY frontend/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend/dist/client /usr/share/nginx/html

USER 101
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget --quiet --output-document=/dev/null http://127.0.0.1:8080/healthz || exit 1
