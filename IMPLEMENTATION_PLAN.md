# Implementation Plan: Consent Manager - Reimplementation - Setup CI

## Overview
Give the repository a GitHub Actions pipeline covering tests, image build and release, modelled on
[FIWARE/contract-management `.github/workflows`](https://github.com/FIWARE/contract-management/tree/main/.github/workflows)
(`test.yml`, `pre-release.yml`, `release.yml`). It reuses the reusable workflows of
[FIWARE/github-workflows](https://github.com/FIWARE/github-workflows) (`semver-version.yml@v1`,
`chart-pr.yml@v1`, `chart-release.yml@v1`). A pull request labelled `major`/`minor`/`patch`
publishes the image `X.Y.Z-PRE-<pr>` and a GitHub pre-release. Merging it to `main` publishes
`X.Y.Z` and a GitHub release.

### Decisions and assumptions (reviewer: object here if any is wrong)
- **Registry** `quay.io`, namespace `fiware` (as in the reference). Both are workflow `env` values
  (`REGISTRY`, `REPOSITORY`), and the image name is `consent-manager`. The secrets are
  `QUAY_USERNAME` / `QUAY_PASSWORD`. `GITHUB_TOKEN` covers the GitHub releases.
- **Versioning** comes from `FIWARE/github-workflows/.github/workflows/semver-version.yml@v1` with
  `tag-prefix: ""`. It takes the highest plain `X.Y.Z` tag (or `0.0.0` when there is none) and
  bumps it by the PR's label. The pom stays `0.1.0-SNAPSHOT` in git. The jobs run
  `versions:set` in their own checkout only and never commit it, as the reference does.
- **Image build** uses `docker/Dockerfile` with `docker/build-push-action`, not the reference's
  `mvn deploy -Poci`: this repo has no `oci` profile and must not gain one. The image is
  multi-arch (`linux/amd64,linux/arm64`), using QEMU and Buildx as in the reference's
  `release.yml`.
- **GitHub releases** use the `gh` CLI (`gh release create`), not
  `marvinpinto/action-automatic-releases`. That action is archived and runs on a deprecated Node
  runtime. `gh` gives the same behaviour: a release titled with the version, `LICENSE` attached,
  and a pre-release replaced when the PR is pushed again.
- **Coveralls** from the reference is left out. The JaCoCo gates in `./mvnw verify` already
  enforce coverage, and no `COVERALLS_TOKEN` exists. The JaCoCo HTML stays an artifact.
- **Action versions** use the majors the existing `build.yml` already uses (`actions/checkout@v4`,
  `actions/setup-java@v4`, `actions/upload-artifact@v4`) and current `docker/*@v3`/`@v6`. They do
  not use the reference's `v1`/`v2`.
- **Chart lint is a local job, not `chart-lint.yml@v1`.** The reusable workflow renders every
  chart with its *default* values and validates the output. `charts/consent-manager` deliberately
  refuses to render without its required inputs (see the header of `values.yaml`; `helm template
  charts/consent-manager` fails on `secrets.dbUser is required`). So the local job runs the same
  tools (`helm lint --strict`, `helm template … | kubeconform -strict`), but only against
  `ci/*-values.yaml`.
- CI can only be fully exercised on GitHub. Every step validates locally with `actionlint` (run as
  shown in FIWARE/github-workflows' own `test.yml`), plus `helm`/`kubeconform` or `docker buildx`
  where relevant. The container needs `sudo chmod 666 /var/run/docker.sock` for Docker.

## Steps

### Step 1: Align the test workflow with the reference and add workflow and chart checks
Rename `.github/workflows/build.yml` to `.github/workflows/test.yml` (`name: Test`) with
`git mv`, and extend it. Do not replace it.

- Keep the `verify` job unchanged in substance: Temurin 25 with the maven cache, the Docker check,
  `./mvnw -B -ntp verify` (which already runs Spotless `check` and both JaCoCo gates), the
  `REQUIRED_ITS` guard, and both artifact uploads.
- Triggers: `pull_request`, `push: branches: [main]`, `workflow_dispatch`, and **`workflow_call`**
  (no inputs), so Step 3 can gate the release on it. Do not add the `labeled`/`unlabeled` PR
  types. A label change must not re-run the ~60-minute suite.
- Concurrency: `group: test-${{ github.event.pull_request.number || github.ref }}` and
  `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`. Pushes to `main` must never
  cancel each other, because Step 3 makes each one a release.
- New job `lint-workflows`: download actionlint (pinned version, the
  `download-actionlint.bash` pattern from FIWARE/github-workflows `test.yml`) and run
  `./actionlint -color`. Pin it with a named `env` value such as `ACTIONLINT_VERSION`, not a
  literal inside the script.
- New job `lint-chart`: `azure/setup-helm@v4` with a pinned `HELM_VERSION` (`v3.17.3`, matching
  FIWARE's default). Install kubeconform at a pinned `KUBECONFORM_VERSION` (`v0.7.0`). Run
  `helm lint --strict charts/consent-manager`, then for each `charts/consent-manager/ci/*-values.yaml`
  run `helm lint --strict -f` and `helm template charts/consent-manager -f <file> | kubeconform -strict -summary`.
  Fail if any of them fails and report every file before exiting, as FIWARE's `eval-charts.sh`
  does. A header comment states why `chart-lint.yml@v1` is not used (see Decisions).
- Update the reference in `docs/testing.md` row 20 (`build.yml` → `test.yml`, job names) and the
  `.github/workflows` entry in `AGENTS.md`.

Acceptance: `actionlint` passes locally. The `lint-chart` commands pass locally against both `ci/`
values files. `grep -rn "build.yml"` finds no stale reference outside `docs/adr/`, because ADRs
are records and are not edited.

### Step 2: Reusable image build and the PR pre-release workflow
- `docker/Dockerfile`: change the build stage to `FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build`.
  The jar is architecture-independent, so only the small runtime stage runs under QEMU for
  `linux/arm64`. Add a short comment saying why. Change nothing else in the runtime contract.
- New `.github/workflows/build-image.yml` (`workflow_call`): input `version` (string, required).
  Secrets `registry-username` / `registry-password`. Env `REGISTRY: quay.io`, `REPOSITORY: fiware`,
  `IMAGE_NAME: consent-manager`, `PLATFORMS: linux/amd64,linux/arm64`. Steps:
  checkout → `setup-java@v4` (Temurin 25, maven cache) →
  `./mvnw -B -ntp versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false` (so the
  packaged jar and any `project.version` carry the release version) →
  `docker/setup-qemu-action@v3` → `docker/setup-buildx-action@v3` → `docker/login-action@v3` →
  `docker/build-push-action@v6` with `context: .`, `file: docker/Dockerfile`, `push: true`,
  `tags: ${REGISTRY}/${REPOSITORY}/${IMAGE_NAME}:${version}`, OCI `labels`
  (`org.opencontainers.image.version`, `.revision`, `.source` = this GitHub repo), and
  `cache-from/to: type=gha`. Output `image` (the full reference) for the release notes. Pass the
  version through `env:`, never by interpolating `${{ }}` straight into `run:` (script injection).
  Step 3 reuses this workflow, so the two release paths cannot drift.
- New `.github/workflows/pre-release.yml`, `name: Pre-Release`:
  - `on: pull_request: types: [opened, synchronize, reopened, labeled, unlabeled]`, with
    concurrency per PR (`pre-release-${{ github.event.pull_request.number }}`, cancel in progress).
  - Top-level `permissions: contents: read`, and each job widens only what it needs.
  - `generate-version`: `uses: FIWARE/github-workflows/.github/workflows/semver-version.yml@v1`,
    `permissions: {contents: read, pull-requests: write}`, `with: {tag-prefix: ""}`. A missing
    label fails this job and comments on the PR. That failure is the label gate.
  - `build-and-push`: `needs: generate-version`, `uses: ./.github/workflows/build-image.yml`,
    `version: ${{ needs.generate-version.outputs.version }}`, with the Quay secrets.
  - `git-release`: `needs: [generate-version, build-and-push]`, `permissions: contents: write`.
    Delete any existing release of the same version together with its tag
    (`gh release delete "$VERSION" --cleanup-tag --yes`, tolerated when absent). Then run
    `gh release create "$VERSION" --prerelease --target "$HEAD_SHA" --title "$VERSION" --notes "Image: <ref>" LICENSE`,
    where `HEAD_SHA` is `github.event.pull_request.head.sha`.
  - `build-and-push` and `git-release` run only when
    `github.event.pull_request.head.repo.full_name == github.repository`. A fork PR has neither the
    Quay secrets nor a branch the tag could point at. `generate-version` still runs for forks, so
    the label is still required.

Acceptance: `actionlint` passes on all workflows. Locally,
`docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile .` succeeds after
`./mvnw versions:set -DnewVersion=0.0.1-PRE-1 -DgenerateBackupPoms=false`. Revert the pom
afterwards; it must not be committed. A single-platform `--load` build is enough when the local
builder cannot do multi-arch. The resulting image starts and its `/app/app.jar` comes from
`consent-manager-0.0.1-PRE-1.jar`.

### Step 3: Release workflow on push to main, gated on the tests
- New `.github/workflows/release.yml`, `name: Release`, `on: push: branches: [main]`,
  `concurrency: {group: release, cancel-in-progress: false}`, so releases run one after another
  and each reads the tags the previous one left.
- Jobs:
  - `test`: `uses: ./.github/workflows/test.yml`. A release is never published from a commit that
    fails `verify`, the chart lint or actionlint. The reference does not gate; this improves on it.
  - `generate-version`: the same `semver-version.yml@v1` call as in Step 2. On a push it resolves
    the merged PR's label and yields `X.Y.Z`. A commit pushed directly to `main` without a merged
    PR fails here, and FIWARE intends that.
  - `build-and-push`: `needs: [test, generate-version]`, `uses: ./.github/workflows/build-image.yml`.
  - `git-release`: `needs: [generate-version, build-and-push]`, `permissions: contents: write`,
    `gh release create "$VERSION" --target "$GITHUB_SHA" --title "$VERSION" --generate-notes LICENSE`
    (not a pre-release).
- In `test.yml`, drop the `push: branches: [main]` trigger, since the release now runs the suite on
  every push to `main` and the suite should not run twice. Keep `pull_request`,
  `workflow_dispatch` and `workflow_call`. Update `docs/testing.md` row 20 so it says the suite
  runs on every PR (`Test`) and on every push to `main` (inside `Release`).

Acceptance: `actionlint` passes. Every `needs:` edge and permission is checked by reading the
workflow graph: `contents: write` only on `git-release`, and `pull-requests: write` only on
`generate-version`. Tag `X.Y.Z` and image `X.Y.Z` come from the same `generate-version` output.

### Step 4: Helm chart versioning and publication via the FIWARE chart workflows
Reuse `chart-pr.yml@v1` and `chart-release.yml@v1` so that the chart is released too. **The
reviewer may drop this step** if chart publication is meant to be a follow-up. Steps 1 to 3 do not
depend on it.

- `pre-release.yml`: add job `chart-version`:
  `uses: FIWARE/github-workflows/.github/workflows/chart-pr.yml@v1`,
  `permissions: {contents: write, pull-requests: write}`. It requires the same label and bumps
  `charts/consent-manager/Chart.yaml` `version` when the PR changes the chart, committing the bump
  to the PR branch. `charts/consent-manager/README.md` is not auto-generated, so do not set
  `helm-docs-version`. Pass `exclude` covering `charts/consent-manager/README.md` and
  `charts/consent-manager/ci/`, so that doc-only and CI-values-only changes do not bump the chart.
- `release.yml`: add job `chart-release`, `needs: test`:
  `uses: FIWARE/github-workflows/.github/workflows/chart-release.yml@v1`,
  `permissions: contents: write`. No OCI push by default. Leave the commented-out
  `oci-registry: quay.io/fiware/helm-charts` with the Quay secrets as the documented opt-in.
  Its GitHub releases are tagged `consent-manager-X.Y.Z`. `next-version.sh` ignores such tags
  because they are not plain `X.Y.Z`, so they do not affect the app versioning.
- `charts/consent-manager/values.yaml`: set the default `image.repository` to
  `quay.io/fiware/consent-manager`, matching `build-image.yml`. Update the `Chart.yaml`
  `appVersion` comment: it is the released image tag the chart pulls by default, and it is set by
  hand in the PR that moves the chart to a new app release. `chart-pr.yml` bumps only `version`.
  `home`/`sources` point at `https://github.com/wistefan/dsc-consent-manager`. Mirror the
  `image.repository` change in `charts/consent-manager/README.md`'s values table.
- Prerequisite, documented in Step 5 and not automatable from the repo: an empty `gh-pages` branch
  and GitHub Pages served from it, which chart-releaser needs.

Acceptance: `actionlint` passes. The Step 1 `lint-chart` commands still pass. `helm template` with
`ci/minimal-values.yaml` renders `quay.io/fiware/consent-manager:<appVersion>`.

### Step 5: Document the CI/release process
- New `docs/ci.md`. For each workflow, give its trigger, jobs and what fails it. Describe the
  label convention (exactly one of `major`/`minor`/`patch` per PR, and the labels must exist in the
  repository) and the version scheme (`X.Y.Z-PRE-<pr>` on a PR, `X.Y.Z` on merge, first release
  bumped from `0.0.0`). List the required secrets (`QUAY_USERNAME`, `QUAY_PASSWORD`) and the
  repository settings: Actions allowed to create releases, the `gh-pages` branch and Pages if
  Step 4 is in, and the fork-PR behaviour. Explain how to change `REGISTRY`/`REPOSITORY`, and the
  deviations from the reference (Dockerfile instead of `-Poci`, `gh` instead of
  `marvinpinto`, local chart lint, no Coveralls).
- `docs/README.md`: add a row under **Contributors** for the CI guide, in the same commit.
- `README.md`: add the `Test` and `Release` workflow status badges, and add a short
  "Releases / container image" line under the getting-started or contributing section that links
  to `docs/ci.md`.
- `docs/deployment.md` and `charts/consent-manager/README.md`: where they say how to obtain the
  image, point at `quay.io/fiware/consent-manager:<version>`.
- `AGENTS.md`: list `.github/workflows/` (`test.yml`, `build-image.yml`, `pre-release.yml`,
  `release.yml`) in Project Structure. Add a Key Conventions bullet: a PR needs a semver label to
  pass the Pre-Release workflow, and the pom version is never committed by CI.

Acceptance: every relative link in the touched Markdown resolves (check with a link-check
one-liner or by hand). No ADR text is edited.
