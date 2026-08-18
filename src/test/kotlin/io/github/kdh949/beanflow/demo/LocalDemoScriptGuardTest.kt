package io.github.kdh949.beanflow.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * The demo scripts' safety behaviour, executed rather than read.
 *
 * The scripts are copied into a temporary root first. `DEMO_ROOT` is derived from the script's own
 * location, so the copy makes every path — the runtime directory the reset deletes, the pid files
 * it signals — point inside the temporary tree. A developer's real `.demo-runtime` and a running
 * demo are therefore untouchable from here, and `docker` is stubbed so nothing is ever removed for
 * real. What is exercised is the real script text.
 */
internal class LocalDemoScriptGuardTest {
    @TempDir
    lateinit var root: Path

    private lateinit var scripts: Path
    private lateinit var stubBin: Path
    private lateinit var dockerLog: Path

    @BeforeEach
    fun copyScriptsAndStubs() {
        root = root.toRealPath()
        scripts = root.resolve("scripts/demo")
        scripts.resolve("lib").createDirectories()
        val source = Path.of("scripts/demo")
        Files.walk(source).use { paths ->
            paths.filter { !it.isDirectory() }.forEach { file ->
                val target = scripts.resolve(source.relativize(file).toString())
                target.parent.createDirectories()
                file.copyTo(target, overwrite = true)
            }
        }
        root.resolve("docker-compose.demo.yml").writeText("services: {}\n")

        stubBin = root.resolve("stub-bin").also { it.createDirectories() }
        dockerLog = root.resolve("docker-invocations.log")
        writeExecutable(stubBin.resolve("pkill"), "#!/usr/bin/env bash\nexit 0\n")
    }

    @Test
    fun `an unknown argument is rejected instead of being ignored`() {
        stubDocker(reportedDatabase = "beanflow_demo")

        val result = run("stop.sh", "--drop-everything")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("Unknown argument")
        assertThat(dockerInvocations()).isEmpty()
    }

    @Test
    fun `reset refuses when the container serves a different database and removes nothing`() {
        stubDocker(reportedDatabase = "some_other_project_db")
        val runtimeDirectory = root.resolve(".demo-runtime").also { it.createDirectories() }
        runtimeDirectory.resolve("jwks.json").writeText("{}")

        val result = run("stop.sh", "--reset")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("Reset refused")
        assertThat(result.output).contains("some_other_project_db")
        // The decisive assertion: no compose down, and the key material is still there.
        assertThat(dockerInvocations()).noneMatch { it.contains("down") }
        assertThat(runtimeDirectory.resolve("jwks.json")).exists()
    }

    @Test
    fun `reset proceeds only for the demo database and then clears the run-time key material`() {
        stubDocker(reportedDatabase = "beanflow_demo")
        val runtimeDirectory = root.resolve(".demo-runtime").also { it.createDirectories() }
        runtimeDirectory.resolve("jwks.json").writeText("{}")

        val result = run("stop.sh", "--reset")

        assertThat(result.exitCode).isZero()
        assertThat(result.output).contains("reset guard passed")
        assertThat(dockerInvocations()).anyMatch { it.contains("down") && it.contains("--volumes") }
        assertThat(runtimeDirectory.exists()).isFalse()
    }

    @Test
    fun `reset failure keeps run-time key material instead of reporting a false success`() {
        stubDocker(reportedDatabase = "beanflow_demo", failComposeOperation = "down")
        val runtimeDirectory = root.resolve(".demo-runtime").also { it.createDirectories() }
        runtimeDirectory.resolve("jwks.json").writeText("{}")

        val result = run("stop.sh", "--reset")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("database removal failed")
        assertThat(result.output).doesNotContain("demo database removed")
        assertThat(runtimeDirectory.resolve("jwks.json")).exists()
    }

    @Test
    fun `reset refuses an unexpected Docker inspect failure instead of treating it as container absence`() {
        stubDocker(reportedDatabase = "beanflow_demo", failInspect = true)
        val runtimeDirectory = root.resolve(".demo-runtime").also { it.createDirectories() }
        runtimeDirectory.resolve("jwks.json").writeText("{}")

        val result = run("stop.sh", "--reset")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("could not inspect")
        assertThat(dockerInvocations()).noneMatch { it.contains(" down ") }
        assertThat(runtimeDirectory.resolve("jwks.json")).exists()
    }

    @Test
    fun `ordinary stop propagates Docker failure instead of claiming the database stopped`() {
        stubDocker(reportedDatabase = "beanflow_demo", failComposeOperation = "stop")

        val result = run("stop.sh")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("database stop failed")
        assertThat(result.output).doesNotContain("database container stopped")
    }

    @Test
    fun `a stale pid file that points to an unrelated sleep process is never signalled`() {
        stubDocker(reportedDatabase = "beanflow_demo")
        val runtimeDirectory = root.resolve(".demo-runtime").also { it.createDirectories() }
        val unrelated = ProcessBuilder("sleep", "30").directory(root.toFile()).start()
        runtimeDirectory.resolve("app.pid").writeText("${unrelated.pid()}\n")

        try {
            val result = run("stop.sh")

            assertThat(result.exitCode).isZero()
            assertThat(result.output).contains("refusing to signal unverified")
            assertThat(unrelated.isAlive).isTrue()
        } finally {
            unrelated.destroyForcibly()
            unrelated.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `frontend launcher leaves a verifiable command marker that stop can own`() {
        stubDocker(reportedDatabase = "beanflow_demo")
        writeExecutable(
            stubBin.resolve("npm"),
            """
            #!/usr/bin/env bash
            trap 'exit 0' TERM INT
            while true; do sleep 1; done
            """.trimIndent() + "\n",
        )
        val launcher = root.resolve("launch-frontend.sh")
        writeExecutable(
            launcher,
            """
            #!/usr/bin/env bash
            set -euo pipefail
            . "${scripts.resolve("lib/common.sh").toAbsolutePath()}"
            mkdir -p "${'$'}DEMO_RUNTIME_DIR"
            start_owned_frontend "${'$'}DEMO_FRONTEND_PID_FILE" frontend "${'$'}DEMO_FRONTEND_LOG"
            """.trimIndent() + "\n",
        )

        val launched = runPath(launcher)
        assertThat(launched.exitCode).isZero()
        val record = root.resolve(".demo-runtime/frontend.pid")
        val pid =
            record
                .readText()
                .lineSequence()
                .single { it.startsWith("pid=") }
                .substringAfter('=')
                .toLong()
        val handle = ProcessHandle.of(pid).orElseThrow()
        try {
            assertThat(handle.isAlive).isTrue()

            val stopped = run("stop.sh")

            assertThat(stopped.exitCode).isZero()
            assertThat(stopped.output).contains("stopped owned frontend process group")
            assertThat(handle.isAlive).isFalse()
        } finally {
            val descendants = handle.descendants().toList()
            handle.destroyForcibly()
            descendants.forEach(ProcessHandle::destroyForcibly)
        }
    }

    @Test
    fun `bootstrap failure containing already is not mistaken for an initialized policy`() {
        stubDocker(reportedDatabase = "beanflow_demo")
        writeIdentityEnv()
        writeExecutable(
            root.resolve("gradlew"),
            """
            #!/usr/bin/env bash
            case "${'$'}*" in
              *local-demo-identity-server*) sleep 3; exit 0 ;;
              *ordinary-accrual-policy-bootstrap*) printf 'Address already in use\n' >&2; exit 9 ;;
              *) exit 0 ;;
            esac
            """.trimIndent() + "\n",
        )
        writeExecutable(
            stubBin.resolve("curl"),
            """
            #!/usr/bin/env bash
            case "${'$'}{@: -1}" in
              */jwks.json) printf '{"keys":[]}' ;;
              */actuator/health) printf '{"status":"UP"}' ;;
              *) exit 1 ;;
            esac
            """.trimIndent() + "\n",
        )

        val result = run("start.sh")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("Policy bootstrap failed")
        assertThat(result.output).doesNotContain("ordinary accrual policy bootstrap completed")
    }

    @Test
    fun `smoke exits non-zero on the first unexpected status instead of reporting a pass`() {
        writeIdentityEnv()
        // Health, CSRF issue, and customer Session login succeed so the script reaches the first
        // product call. Nearby discovery then answers 500 where 200 is required.
        writeExecutable(
            stubBin.resolve("curl"),
            """
            #!/usr/bin/env bash
            out=""
            headers=""
            prev=""
            for argument in "${'$'}@"; do
              if [ "${'$'}prev" = "-o" ]; then out="${'$'}argument"; fi
              if [ "${'$'}prev" = "-D" ]; then headers="${'$'}argument"; fi
              prev="${'$'}argument"
            done
            url="${'$'}{@: -1}"
            case "${'$'}url" in
              */actuator/health) printf '{"status":"UP"}'; exit 0 ;;
              */auth/customer/csrf)
                printf 'Set-Cookie: BEANFLOW_CUSTOMER_XSRF=stub-xsrf; Path=/; Secure\r\n' > "${'$'}headers"
                [ -n "${'$'}out" ] && : > "${'$'}out"
                printf '204'
                exit 0
                ;;
              */auth/customer/sessions)
                printf 'Set-Cookie: BEANFLOW_CUSTOMER_SESSION=stub-session; Path=/; Secure; HttpOnly\r\n' > "${'$'}headers"
                printf '{"actorType":"CUSTOMER","displayName":"BeanFlow Demo Customer"}' > "${'$'}out"
                printf '200'
                exit 0
                ;;
            esac
            [ -n "${'$'}out" ] && printf '{"stub":true}' > "${'$'}out"
            printf '500'
            exit 0
            """.trimIndent() + "\n",
        )

        val result = run("smoke.sh")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("[fail]")
        assertThat(result.output).contains("nearby stores", "expected 200 got 500")
        assertThat(result.output).contains("customer Session established without JWT paste")
        // It stopped at the first failure rather than walking the rest of the flow.
        assertThat(result.output).doesNotContain("core smoke flow completed")
        assertThat(result.output).doesNotContain("create order")
    }

    @Test
    fun `smoke rejects an unknown checkpoint instead of silently running another contract`() {
        writeIdentityEnv()

        val result = run("smoke.sh", "--unknown-checkpoint")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("Unknown argument")
    }

    @Test
    fun `resource mismatch failure does not print either internal identifier`() {
        val expected = "11111111-1111-4111-8111-111111111111"
        val actual = "22222222-2222-4222-8222-222222222222"
        val probe = root.resolve("resource-mismatch-probe.sh")
        writeExecutable(
            probe,
            """
            #!/usr/bin/env bash
            set -euo pipefail
            . "${scripts.resolve("lib/common.sh").toAbsolutePath()}"
            same_resource_or_fail "$actual" "$expected" "Order replay returned a different resource."
            """.trimIndent() + "\n",
        )

        val result = runPath(probe)

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("Order replay returned a different resource.")
        assertThat(result.output).doesNotContain(actual, expected)
    }

    @Test
    fun `customer checkpoint verifies approved payment and stops before merchant operations`() {
        writeIdentityEnv()
        val curlLog = root.resolve("smoke-curl.log")
        writeExecutable(
            stubBin.resolve("curl"),
            """
            #!/usr/bin/env bash
            out=""
            headers=""
            method="GET"
            body=""
            previous=""
            for argument in "${'$'}@"; do
              case "${'$'}previous" in
                -o) out="${'$'}argument" ;;
                -D) headers="${'$'}argument" ;;
                -X) method="${'$'}argument" ;;
                -d) body="${'$'}argument" ;;
              esac
              previous="${'$'}argument"
            done
            url="${'$'}{@: -1}"
            printf '%s %s\n' "${'$'}method" "${'$'}url" >> "${curlLog.toAbsolutePath()}"

            if [[ "${'$'}url" == */actuator/health ]]; then
              printf '{"status":"UP"}'
              exit 0
            fi

            respond() {
              [ -z "${'$'}out" ] || printf '%s' "${'$'}2" > "${'$'}out"
              printf '%s' "${'$'}1"
            }

            case "${'$'}url" in
              */auth/customer/csrf)
                printf 'Set-Cookie: BEANFLOW_CUSTOMER_XSRF=stub-xsrf; Path=/; Secure\r\n' > "${'$'}headers"
                respond 204 ''
                ;;
              */auth/customer/sessions)
                printf 'Set-Cookie: BEANFLOW_CUSTOMER_SESSION=stub-session; Path=/; Secure; HttpOnly\r\n' > "${'$'}headers"
                respond 200 '{"actorType":"CUSTOMER","displayName":"BeanFlow Demo Customer"}'
                ;;
              */stores/nearby*)
                respond 200 '{"items":[{"storeId":"d1000000-0000-4000-8000-000000000001","distanceMeters":1}]}'
                ;;
              */stores/d1000000-0000-4000-8000-000000000001/menus)
                respond 200 '{"items":[{"menuId":"d2000000-0000-4000-8000-000000000001","available":true},{"menuId":"d2000000-0000-4000-8000-000000000002","available":false}]}'
                ;;
              */me/coupons*)
                respond 200 '{"items":[{"state":"AVAILABLE","applicable":true}]}'
                ;;
              */stores/d1000000-0000-4000-8000-000000000001/pickup-slots)
                respond 200 '{"items":[{"pickupSlotId":"d6000000-0000-4000-8000-000000000001"}]}'
                ;;
              */point-accounts/d7000000-0000-4000-8000-000000000001/transactions*)
                respond 200 '{"items":[]}'
                ;;
              */point-accounts/d7000000-0000-4000-8000-000000000001)
                respond 200 '{"availablePointsKrw":0}'
                ;;
              */orders/*/payment-attempts)
                respond 200 "{\"paymentId\":\"payment-1\",\"providerOrderId\":\"provider-order-1\",\"amount\":{\"value\":10000},\"state\":\"READY\",\"method\":\"CARD\",\"successUrl\":\"${'$'}DEMO_FRONTEND_BASE_URL/app/payments/payment-1/success\",\"failUrl\":\"${'$'}DEMO_FRONTEND_BASE_URL/app/payments/payment-1/fail\"}"
                ;;
              */orders)
                if [[ "${'$'}body" == *'"optionIds":[]'* ]]; then
                  respond 409 '{"code":"IDEMPOTENCY_KEY_REUSED"}'
                else
                  respond 201 '{"order":{"orderId":"order-1","lines":[{"orderLineId":"line-1"}]}}'
                fi
                ;;
              */payment-config)
                respond 200 '{"provider":"TOSS_PAYMENTS","sdkVersion":"V2_STANDARD","clientKey":"test_ck_local_scripted"}'
                ;;
              */payments/payment-1/confirmations)
                if [[ "${'$'}body" == *'"amount":10001'* ]]; then
                  respond 409 '{"code":"IDEMPOTENCY_KEY_REUSED"}'
                else
                  respond 200 '{"paymentId":"payment-1","approvalState":"APPROVED","orderReference":"BF-D3M2-S9F4"}'
                fi
                ;;
              */payments/payment-1)
                respond 200 '{"paymentId":"payment-1","approvalState":"APPROVED","orderReference":"BF-D3M2-S9F4"}'
                ;;
              */store-orders/*)
                respond 599 '{"code":"MERCHANT_OPERATION_MUST_NOT_RUN"}'
                ;;
              *)
                respond 598 '{"code":"UNEXPECTED_STUB_PATH"}'
                ;;
            esac
            """.trimIndent() + "\n",
        )

        val result = run("smoke.sh", "--customer-checkpoint")

        assertThat(result.exitCode).withFailMessage(result.output).isZero()
        assertThat(result.output).contains("approved payment query", "customer checkpoint completed")
        assertThat(result.output).doesNotContain("store fulfilment", "core smoke flow completed")
        assertThat(curlLog.readText()).contains("/api/v1/payments/payment-1")
        assertThat(curlLog.readText()).doesNotContain("/store-orders/")
    }

    private fun writeIdentityEnv() {
        val runtimeDirectory = root.resolve(".demo-runtime").also { it.createDirectories() }
        runtimeDirectory.resolve("demo-identity.env").writeText(
            """
            BEANFLOW_DEMO_JWKS_URI=http://127.0.0.1:1/jwks.json
            BEANFLOW_DEMO_CURSOR_SECRET=stub
            BEANFLOW_DEMO_ISSUER=http://127.0.0.1:18081
            BEANFLOW_DEMO_WORKLOAD_AUDIENCE=beanflow-local-demo
            BEANFLOW_DEMO_WORKLOAD_SUBJECT=local-demo-bootstrap
            BEANFLOW_DEMO_DEPLOYMENT_RUN_CLAIM=local-demo-run
            """.trimIndent() + "\n",
        )
    }

    private fun stubDocker(
        reportedDatabase: String,
        failComposeOperation: String? = null,
        failInspect: Boolean = false,
    ) {
        val containerState = root.resolve("demo-container-present")
        containerState.writeText("present\n")
        writeExecutable(
            stubBin.resolve("docker"),
            """
            #!/usr/bin/env bash
            printf '%s\n' "${'$'}*" >> "${dockerLog.toAbsolutePath()}"
            if [ "${'$'}1" = "inspect" ]; then
              if [ "$failInspect" = "true" ]; then
                printf 'injected inspect failure\n' >&2
                exit 99
              fi
              if [ ! -f "${containerState.toAbsolutePath()}" ]; then
                printf 'Error: No such object: %s\n' "${'$'}{@: -1}" >&2
                exit 1
              fi
              if [ "${'$'}2" = "--format" ] && [[ "${'$'}3" == *"com.docker.compose.project"* ]]; then
                printf '%s\n' "${'$'}DEMO_COMPOSE_PROJECT"
              else
                printf 'POSTGRES_DB=%s\n' "$reportedDatabase"
              fi
              exit 0
            fi
            if [ "${'$'}1" = "compose" ]; then
              if [[ " ${'$'}* " == *" ${failComposeOperation ?: "__never__"} "* ]]; then
                printf 'injected compose %s failure\n' "${failComposeOperation ?: "__never__"}" >&2
                exit 99
              fi
              if [[ " ${'$'}* " == *" down "* ]]; then
                rm -f "${containerState.toAbsolutePath()}"
              fi
            fi
            exit 0
            """.trimIndent() + "\n",
        )
    }

    private fun dockerInvocations(): List<String> =
        if (dockerLog.exists()) dockerLog.readText().lines().filter { it.isNotBlank() } else emptyList()

    private fun writeExecutable(
        path: Path,
        content: String,
    ) {
        path.writeText(content)
        path.setPosixFilePermissions(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }

    private fun run(
        script: String,
        vararg arguments: String,
    ): ScriptResult = runPath(scripts.resolve(script), *arguments)

    private fun runPath(
        script: Path,
        vararg arguments: String,
    ): ScriptResult {
        val process =
            ProcessBuilder(listOf("bash", script.toString()) + arguments)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .also { it.environment()["PATH"] = "${stubBin.toAbsolutePath()}:${System.getenv("PATH")}" }
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "Script $script did not finish" }
        return ScriptResult(process.exitValue(), output)
    }

    private data class ScriptResult(
        val exitCode: Int,
        val output: String,
    )
}
