package com.seamware.consentmanager.api;

import java.nio.file.Path;

/** Where the frozen oasdiff baseline lives; scans of the authored spec tree skip it. */
final class SpecBaseline {

    /** Holds a whole regenerated document, never hand-edited, so authored-spec guards skip it. */
    static final Path DIRECTORY = Path.of("api", "baseline");

    /** The document {@link SpecCompatibilityIT} diffs the build's bundle against. */
    static final Path DOCUMENT = DIRECTORY.resolve("openapi.yaml");

    private SpecBaseline() {}
}
