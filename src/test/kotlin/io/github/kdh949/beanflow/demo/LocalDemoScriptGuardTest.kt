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
    fun `smoke exits non-zero on the first unexpected status instead of reporting a pass`() {
        writeIdentityEnv()
        // Health answers UP so the script gets past its precondition; the first API call then
        // answers 500 where 200 is required.
        writeExecutable(
            stubBin.resolve("curl"),
            """
            #!/usr/bin/env bash
            out=""
            prev=""
            for argument in "${'$'}@"; do
              if [ "${'$'}prev" = "-o" ]; then out="${'$'}argument"; fi
              prev="${'$'}argument"
            done
            url="${'$'}{@: -1}"
            case "${'$'}url" in
              */actuator/health) printf '{"status":"UP"}'; exit 0 ;;
            esac
            [ -n "${'$'}out" ] && printf '{"stub":true}' > "${'$'}out"
            printf '500'
            exit 0
            """.trimIndent() + "\n",
        )

        val result = run("smoke.sh")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.output).contains("[fail]")
        assertThat(result.output).contains("expected 200 got 500")
        // It stopped at the first failure rather than walking the rest of the flow.
        assertThat(result.output).doesNotContain("smoke flow completed")
        assertThat(result.output).doesNotContain("create order")
    }

    private fun writeIdentityEnv() {
        val runtimeDirectory = root.resolve(".demo-runtime").also { it.createDirectories() }
        runtimeDirectory.resolve("demo-identity.env").writeText(
            """
            CUSTOMER_TOKEN=stub-token
            STORE_OWNER_TOKEN=stub-token
            OTHER_STORE_OWNER_TOKEN=stub-token
            BEANFLOW_DEMO_JWKS_URI=http://127.0.0.1:1/jwks.json
            BEANFLOW_DEMO_CURSOR_SECRET=stub
            """.trimIndent() + "\n",
        )
    }

    private fun stubDocker(reportedDatabase: String) {
        writeExecutable(
            stubBin.resolve("docker"),
            """
            #!/usr/bin/env bash
            printf '%s\n' "${'$'}*" >> "${dockerLog.toAbsolutePath()}"
            if [ "${'$'}1" = "inspect" ]; then
              printf 'POSTGRES_DB=%s\n' "$reportedDatabase"
              exit 0
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
    ): ScriptResult {
        val process =
            ProcessBuilder(listOf("bash", scripts.resolve(script).toString()) + arguments)
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
