import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Date;

/** Ephemeral test issuer only. No signing key is persisted or used outside the isolated test network. */
public class BootstrapIdentityFixture {
    public static void main(String[] args) throws Exception {
        var directory = Path.of(args[0]);
        var key = new RSAKeyGenerator(2048).keyID("runtime-fixture").generate();
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer("https://release.example.test").audience("beanflow-bootstrap")
                .subject("runtime-fixture").claim("run_id", "isolated-startup")
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300))).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(key));
        Files.writeString(directory.resolve("token"), token.serialize());
        Files.writeString(directory.resolve("jwks.json"), new JWKSet(key.toPublicJWK()).toString());
        for (var name : new String[] {"token", "jwks.json"}) {
            Files.setPosixFilePermissions(directory.resolve(name), PosixFilePermissions.fromString("r--------"));
        }
    }
}
