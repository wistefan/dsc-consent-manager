package com.seamware.consentmanager.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The contract documents the Contract Service tests are written against, read from {@code
 * src/test/resources/contracts}.
 *
 * <p>Files rather than string literals so the deserialization tests, the stubbed upstream and the
 * extraction tests all describe the same documents, and a change to one of them is visible as a
 * diff of the document rather than scattered across suites.
 */
public final class ContractFixtures {

    /** An ecosystem contract: offerings with policies, a purpose list and signed members. */
    public static final String ECOSYSTEM = "ecosystem-contract.json";

    /** A bilateral contract: two named parties, a purpose list and terms and conditions. */
    public static final String BILATERAL = "bilateral-contract.json";

    /** An ecosystem contract identified by mongo's {@code _id} rather than by {@code uid}. */
    public static final String ECOSYSTEM_MONGO_ID = "ecosystem-contract-mongo-id.json";

    /** A bilateral contract carrying a top-level property no model component covers. */
    public static final String BILATERAL_UNKNOWN_PROPERTY =
            "bilateral-contract-unknown-property.json";

    /** A bilateral contract with policies but no purpose list, and no terms and conditions. */
    public static final String BILATERAL_NO_PURPOSE = "bilateral-contract-no-purpose.json";

    /** A bilateral contract naming its two parties and nothing else. */
    public static final String BILATERAL_MINIMAL = "bilateral-contract-minimal.json";

    /** An ecosystem contract whose purposes state different retention terminations. */
    public static final String ECOSYSTEM_DIVERGENT_TERMINATION =
            "ecosystem-contract-divergent-termination.json";

    /** An ecosystem contract with policies but no purpose list, and one revoked member. */
    public static final String ECOSYSTEM_NO_PURPOSE = "ecosystem-contract-no-purpose.json";

    /** An ecosystem contract naming no ecosystem, so only its identifier can title it. */
    public static final String ECOSYSTEM_UNTITLED = "ecosystem-contract-untitled.json";

    /** Classpath directory the fixture documents live in. */
    private static final String DIRECTORY = "contracts/";

    private ContractFixtures() {}

    /** Reads a fixture document; fails loudly rather than returning an empty body. */
    public static String body(String fixture) {
        String resource = DIRECTORY + fixture;
        try (InputStream stream =
                ContractFixtures.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException(
                        "No contract fixture on the classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read contract fixture " + resource, e);
        }
    }
}
