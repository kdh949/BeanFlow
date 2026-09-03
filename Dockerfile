FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
ADD --checksum=sha256:bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar \
    /workspace/opentelemetry-javaagent.jar
ADD --checksum=sha256:319b9a669736d22c77a495925ebab999d0787a44286353feb98b24500b7e26c6 \
    https://github.com/grafana/otel-profiling-java/releases/download/v2.1.2/pyroscope-otel-javaagent-extension.jar \
    /workspace/pyroscope-otel-javaagent-extension.jar
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY scripts/ci/test-class-weights.tsv ./scripts/ci/test-class-weights.tsv
RUN chmod 0755 gradlew
COPY src ./src
RUN ./gradlew --no-daemon bootJar \
    && find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -exec cp '{}' /workspace/app.jar \;

FROM hashicorp/vault:2.0.4 AS vault

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl tini util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 beanflow \
    && useradd --system --uid 10001 --gid beanflow --home-dir /opt/beanflow --shell /usr/sbin/nologin beanflow \
    && groupadd --system --gid 10002 vault-proxy \
    && useradd --system --uid 10002 --gid vault-proxy --home-dir /nonexistent --shell /usr/sbin/nologin vault-proxy \
    && install --directory --owner=root --group=root --mode=0700 /run/beanflow-vault-bootstrap \
    && install --directory --owner=root --group=root --mode=0700 /run/beanflow-vault

WORKDIR /opt/beanflow
COPY --from=vault /bin/vault /usr/local/bin/vault
COPY --from=build --chown=beanflow:beanflow /workspace/app.jar ./app.jar
COPY --from=build --chown=beanflow:beanflow /workspace/opentelemetry-javaagent.jar ./opentelemetry-javaagent.jar
COPY --from=build --chown=beanflow:beanflow /workspace/pyroscope-otel-javaagent-extension.jar ./pyroscope-otel-javaagent-extension.jar
COPY --chown=root:root deploy/backend/entrypoint.sh /usr/local/bin/beanflow-entrypoint
COPY --chown=root:root deploy/vault/proxy.hcl /etc/beanflow/vault-proxy.hcl

RUN chmod 0555 /usr/local/bin/vault /usr/local/bin/beanflow-entrypoint \
    && chmod 0444 /etc/beanflow/vault-proxy.hcl

USER root
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/beanflow-entrypoint"]
