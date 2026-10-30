# API compatibility

[README](../README.md) › [Documentation](README.md) › API compatibility

The published contract is the bundled specification served at `GET /openapi.yaml`. A change that
would break an existing client must be a decision someone made, not an accident in a refactor.

## How it is enforced

`SpecCompatibilityIT` runs a pinned `tufin/oasdiff` image through Testcontainers:

```
oasdiff breaking --fail-on ERR api/baseline/openapi.yaml target/openapi-bundle/openapi.yaml
```

Any error-level (`ERR`) finding fails `./mvnw verify`, and the assertion message lists every
finding's level, rule id, operation and path. Warnings (`WARN`) and informational changes are
listed in that message but do not fail the build; the test gates on the `ERR` findings and on
oasdiff's exit status, which `--fail-on ERR` sets. The same test proves the detection works. It
applies one edit at a time to the baseline and checks each result: a removed operation, a removed
response property and a newly required request property are reported as `ERR` and fail; a removed
optional request property is reported as `WARN` and passes; and an added optional request property
is not reported at all.

## Acknowledging a breaking change

1. Make the change in `api/` and confirm `SpecCompatibilityIT` reports exactly the findings you
   intend.
2. Regenerate the baseline from the build's bundle:

   ```bash
   ./mvnw generate-resources
   cp target/openapi-bundle/openapi.yaml api/baseline/openapi.yaml
   ```

3. Commit the baseline in the same PR as the change, and say in the PR description which clients
   are affected. The baseline diff is the record that the break was accepted.

Refresh the baseline the same way on every release, so it always reflects what clients were last
promised. Never edit `api/baseline/openapi.yaml` by hand. It is not served, and the reference and
lint tests ignore it.

## Upgrading oasdiff

The image tag is pinned in `SpecCompatibilityIT.OASDIFF_IMAGE`, because a release can change which
changes count as breaking. Bump it deliberately and run the test before merging.

## Related documents

- [OpenAPI specification](../api/openapi.yaml): the contract the baseline is compared against.
- [Security Architecture — Spec layout](security.md#spec-layout): how the specification is split across files and bundled.
- [Testing](testing.md): how the suite, `SpecCompatibilityIT` included, is organised.
