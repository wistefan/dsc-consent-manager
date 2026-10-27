# Compatibility baseline

`openapi.yaml` here is the bundled specification as last acknowledged: a byte copy of
`target/openapi-bundle/openapi.yaml`, never hand-edited and never served. `SpecCompatibilityIT`
runs oasdiff against it and fails `./mvnw verify` on any error-level breaking change. To accept a
breaking change deliberately, regenerate this file in the same PR so the reviewer sees the change
in the diff, and refresh it on every release. See `docs/api-compatibility.md`.
