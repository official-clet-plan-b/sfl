package gh.edu.clet.sfl.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Proves the decoder actually gives up within the configured bound rather than hanging on a stalled
 * identity provider - see {@link TimeoutBoundedJwtDecoders} for why the default (Boot-auto-configured)
 * decoder cannot make this guarantee.
 */
class TimeoutBoundedJwtDecodersTest {

    private static final Duration BOUND = Duration.ofMillis(300);

    @Test
    void a_stalled_identity_provider_fails_within_the_configured_timeout_instead_of_hanging() throws IOException {
        // Accepts the TCP connection (so this exercises the read timeout, not the connect timeout) and
        // then never writes a response - exactly what a Zitadel instance that accepted the connection but
        // stalled mid-request looks like from this service's side.
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Thread.sleep(Duration.ofSeconds(30).toMillis());
                } catch (IOException | InterruptedException expected) {
                    // Test teardown closes the server socket; either exception here is expected noise.
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            JwtDecoder decoder = TimeoutBoundedJwtDecoders.fromIssuerLocation(
                    "http://localhost:" + serverSocket.getLocalPort(), BOUND, BOUND);

            Instant start = Instant.now();
            assertThatThrownBy(() -> decoder.decode("not-a-real-token")).isInstanceOf(RuntimeException.class);
            Duration elapsed = Duration.between(start, Instant.now());

            // Generous headroom above the 300ms bound so this is not flaky under CI scheduling jitter,
            // while still failing if the read timeout were not wired through at all (which would hang
            // for however long the JVM's default socket timeout is - effectively forever).
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void keys_from_an_internal_jwks_uri_validate_tokens_carrying_the_public_issuer() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        server.start();
        try {
            String jwkSetUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
            JwtDecoder decoder = TimeoutBoundedJwtDecoders.fromIssuerLocation(
                    "http://localhost:8080/realms/sfl", jwkSetUri, Duration.ofSeconds(2), Duration.ofSeconds(2));

            assertThat(decoder.decode(sign(key, "http://localhost:8080/realms/sfl")).getSubject()).isEqualTo("user-1");
            assertThatThrownBy(() -> decoder.decode(sign(key, "http://idp:8080/realms/sfl")))
                    .isInstanceOf(JwtValidationException.class);
        } finally {
            server.stop(0);
        }
    }

    private static String sign(RSAKey key, String issuer) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().subject("user-1").issuer(issuer)
                        .expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
