package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A per-run self-signed certificate for an HTTPS webhook stub on {@code localhost}, trusted only by
 * the clients a test configures with {@link #trustStore()}; main config never trusts it.
 */
record WebhookStubTls(Path keystore, Path truststore) {

    static final String STORE_PASSWORD = "webhook-test";

    static final String STORE_TYPE = "PKCS12";

    private static final String CERT_ALIAS = "webhook-stub";

    private static final String VALIDITY_DAYS = "1";

    /** Generates a fresh key pair and a truststore holding only its certificate. */
    static WebhookStubTls generate() throws IOException, InterruptedException {
        Path stores = Files.createTempDirectory("webhook-tls");
        WebhookStubTls tls =
                new WebhookStubTls(
                        stores.resolve("keystore.p12"), stores.resolve("truststore.p12"));
        Path certificate = stores.resolve("stub.pem");
        keytool(
                "-genkeypair",
                "-alias",
                CERT_ALIAS,
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost,ip:127.0.0.1",
                "-validity",
                VALIDITY_DAYS,
                "-keystore",
                tls.keystore().toString(),
                "-storetype",
                STORE_TYPE,
                "-storepass",
                STORE_PASSWORD,
                "-keypass",
                STORE_PASSWORD);
        keytool(
                "-exportcert",
                "-rfc",
                "-alias",
                CERT_ALIAS,
                "-keystore",
                tls.keystore().toString(),
                "-storetype",
                STORE_TYPE,
                "-storepass",
                STORE_PASSWORD,
                "-file",
                certificate.toString());
        keytool(
                "-importcert",
                "-noprompt",
                "-alias",
                CERT_ALIAS,
                "-file",
                certificate.toString(),
                "-keystore",
                tls.truststore().toString(),
                "-storetype",
                STORE_TYPE,
                "-storepass",
                STORE_PASSWORD);
        return tls;
    }

    /** {@link #generate()} for class initialisation, where a checked exception cannot escape. */
    static WebhookStubTls generateUnchecked() {
        try {
            return generate();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Context properties under which the webhook client trusts this certificate. */
    Map<String, String> clientProperties() {
        return Map.of(TrustingWebhookClientFactory.TRUSTSTORE, truststore.toString());
    }

    /** {@code options} serving HTTPS only, with this certificate. */
    WireMockConfiguration serve(WireMockConfiguration options) {
        return options.httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(keystore.toString())
                .keystoreType(STORE_TYPE)
                .keystorePassword(STORE_PASSWORD)
                .keyManagerPassword(STORE_PASSWORD);
    }

    /** A client truststore admitting only this certificate. */
    SslConfiguration.TrustStoreConfiguration trustStore() {
        return trustStore(truststore);
    }

    /** A client truststore read from {@code truststore}, as {@link #generate()} writes it. */
    static SslConfiguration.TrustStoreConfiguration trustStore(Path truststore) {
        SslConfiguration.TrustStoreConfiguration trust =
                new SslConfiguration.TrustStoreConfiguration();
        trust.setPath("file:" + truststore);
        trust.setPassword(STORE_PASSWORD);
        trust.setType(STORE_TYPE);
        return trust;
    }

    /** Removes the generated stores. */
    void delete() throws IOException {
        try (Stream<Path> files = Files.list(keystore.getParent())) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(keystore.getParent());
    }

    private static void keytool(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}
