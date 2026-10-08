package com.seamware.consentmanager.support;

import com.seamware.consentmanager.client.dto.ContractResponse;
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

    /**
     * An ecosystem contract declaring third-party disclosure without naming the third party, whose
     * purposes also state no purpose text of their own.
     */
    public static final String ECOSYSTEM_UNNAMED_DISCLOSURE =
            "ecosystem-contract-unnamed-disclosure.json";

    /** An ecosystem contract that states terms and conditions, which that shape usually omits. */
    public static final String ECOSYSTEM_TERMS = "ecosystem-contract-terms.json";

    /** An ecosystem contract one of whose members has not signed yet. */
    public static final String ECOSYSTEM_UNSIGNED_MEMBER =
            "ecosystem-contract-unsigned-member.json";

    /**
     * An ecosystem contract evidencing neither side: its members carry no role and it names no
     * service offering, so nothing in it contradicts whichever direction a request declares.
     */
    public static final String ECOSYSTEM_ROLELESS = "ecosystem-contract-roleless.json";

    /** Classpath directory the fixture documents live in. */
    private static final String DIRECTORY = "contracts/";

    private ContractFixtures() {}

    /** A fixture decoded with the application's own mapper, as a fetch would decode it. */
    public static ContractResponse contract(String fixture) {
        return decode(body(fixture));
    }

    /** A contract document decoded as served, for a caller that altered one first. */
    public static ContractResponse decode(String document) {
        try {
            return TestJson.mapper().readValue(document, ContractResponse.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode contract document", e);
        }
    }

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
