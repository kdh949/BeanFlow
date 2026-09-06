import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import io.github.kdh949.beanflow.shared.api.*;
import io.github.kdh949.beanflow.shared.internal.VaultTransitPersonalDataAdapter;
import io.github.kdh949.beanflow.shared.internal.VaultTransitPersonalDataProperties;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Runs real Spring binding and the production Transit adapter as the image's beanflow user. */
public class EntrypointProbe {
    private static final String[] NAMES = {
        "BEANFLOW_DB_PASSWORD", "BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL",
        "BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL", "BEANFLOW_AISTOR_ACCESS_KEY",
        "BEANFLOW_AISTOR_SECRET_KEY", "TOSS_CLIENT_KEY", "TOSS_SECRET_KEY"
    };

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static void main(String[] args) throws Exception {
        require("beanflow".equals(System.getProperty("user.name")), "Wrong JVM user");
        require("preserved".equals(System.getenv("BEANFLOW_ENTRYPOINT_TEST_SETTING")), "App environment lost");
        require("-Xms64m".equals(System.getenv("JAVA_TOOL_OPTIONS")), "JVM options lost");
        String configImport = System.getenv("SPRING_CONFIG_IMPORT");
        Path directory = Path.of(configImport.substring("configtree:".length()));
        // Bind the same datasource placeholder that failed during deployment, using the image's Spring libraries.
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new ConfigTreePropertySource("runtime-secrets", directory));
        environment.getPropertySources().addFirst(new MapPropertySource("datasource", Map.of(
                "spring.datasource.password", "${BEANFLOW_DB_PASSWORD}")));
        String password = Binder.get(environment).bind("spring.datasource.password", String.class).get();
        require(password.equals(expected("BEANFLOW_DB_PASSWORD")), "Datasource secret binding differs");
        var deploymentEnvironment = new StandardEnvironment();
        deploymentEnvironment.getPropertySources().addFirst(new MapPropertySource("deployment-profile", Map.of(
                "spring.profiles.active", "portfolio")));
        ConfigDataEnvironmentPostProcessor.applyTo(deploymentEnvironment);
        require(expected("BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL").equals(
                deploymentEnvironment.getProperty("beanflow.authentication.attempt-hmac-key-base64-url")),
                "Packaged deployment profile replaced the injected authentication HMAC key");
        require(Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------")),
                "Runtime directory permissions differ");
        for (String name : NAMES) {
            Path file = directory.resolve(name);
            require(Files.readString(file).equals(expected(name)), "Secret bytes differ: " + name);
            require(Files.getOwner(file).getName().equals("beanflow"), "Secret owner differs: " + name);
            require(Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("r--------")),
                    "Runtime secret permissions differ: " + name);
            require(!Files.isReadable(Path.of("/run/secrets", name)), "JVM can read bootstrap secret: " + name);
        }
        for (String vaultDirectory : new String[] {"/run/beanflow-vault", "/run/beanflow-vault-bootstrap"}) {
            require(!Files.isReadable(Path.of(vaultDirectory, "BEANFLOW_VAULT_ROLE_ID")), "Vault credential exposed");
        }
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8100/v1/auth/token/lookup-self"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            boolean authenticated = false;
            for (int attempt = 0; attempt < 20; attempt++) {
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    authenticated = true;
                    break;
                }
                Thread.sleep(200);
            }
            require(authenticated, "Vault Proxy did not authenticate the JVM request with AppRole");
        }
        validateProductionAdapter();
        Files.createFile(Path.of("/tmp/beanflow-entrypoint-test-passed"));
    }

    private static void validateProductionAdapter() {
        var properties = new VaultTransitPersonalDataProperties(
                URI.create("http://127.0.0.1:8100"), "transit", "beanflow-personal-data",
                "beanflow-blind-index", 1, Set.of(1), Duration.ofSeconds(2), Duration.ofSeconds(3));
        // Test-only telemetry: provider calls still execute through the production adapter and real TLS Vault.
        var telemetry = new ExternalDependencyTelemetry() {
            @Override
            public <T> T observe(ExternalDependencyCall call,
                    Function1<? super T, ? extends ExternalDependencyOutcome> outcomeOf,
                    Function0<? extends T> block) {
                return block.invoke();
            }
        };
        var adapter = new VaultTransitPersonalDataAdapter(properties, new ObjectMapper(), telemetry);
        adapter.validateStartup();
        var context = new PersonalDataEncryptionContext(PersonalDataOwnerContext.IDENTITY,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), PersonalDataField.DISPLAY_NAME, 1);
        byte[] plaintext = "entrypoint-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var encrypted = adapter.encrypt(plaintext, context);
        require(Arrays.equals(adapter.decrypt(encrypted, context), plaintext), "Transit round trip differs");
        var value = PersonalDataNormalizer.INSTANCE.normalize(ExactSearchCriterionType.EMAIL, "fixture@example.test");
        var indexes = adapter.generate(value, Set.of(1));
        require(indexes.size() == 1 && indexes.get(0).digestBytes().length == 32, "Transit HMAC differs");
        // Vault 2.0.4 rewrap does not pass AAD to its cipher. Preserve the explicit failure, never drop AAD.
        boolean rewrapRejected = false;
        try {
            adapter.rewrap(encrypted, context);
        } catch (DomainFailure failure) {
            require(failure.getCode() == FailureCode.DEPENDENCY_UNAVAILABLE, "Unexpected rewrap failure");
            rewrapRejected = true;
        }
        require(rewrapRejected, "Vault rewrap behavior changed; revalidate AAD compatibility before updating this test");
    }

    private static String expected(String name) {
        return "fixture-" + name + "-#$=\\ value\nnext";
    }
}
