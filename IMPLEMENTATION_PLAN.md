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
- Concurrency: `group: test-${{ github.event.pull_request.number || github.run_id }}` and
  `cancel-in-progress: true`. A PR shares one group, so a new push cancels its stale run. Every
  other event (push, dispatch, and the `workflow_call` from Step 3) gets a group of its own run, so
  it is never queued, cancelled or superseded. Do not key non-PR runs on `github.ref`: a GitHub
  concurrency group keeps at most one pending run and cancels the older pending one, so a shared
  `main` group would silently drop runs even with `cancel-in-progress: false`.
- New job `lint-workflows`: download actionlint (pinned version, the
  `download-actionlint.bash` pattern from FIWARE/github-workflows `test.yml`) and run
  `./actionlint -color`. Pin it with a named `env` value such as `ACTIONLINT_VERSION`, not a
  literal inside the script.
- New job `lint-chart`: `azure/setup-helm@v4` with a pinned `HELM_VERSION` (`v3.17.3`, matching
  FIWARE's default). Install kubeconform at a pinned `KUBECONFORM_VERSION` (`v0.7.0`). Run
  `helm lint --strict charts/consent-manager`, then for each `charts/consent-manager/ci/*-values.yaml`
  run `helm lint --strict -f` and `helm template charts/consent-manager -f <file> | kubeconform`.
  The kubeconform call takes the flags FIWARE's `eval-charts.sh` uses, so the CRDs the chart
  renders (`ci/all-enabled-values.yaml` emits a `ServiceMonitor`) have a schema:
  `-strict -summary -schema-location default -schema-location "$CRD_SCHEMA_LOCATION"`, with
  `CRD_SCHEMA_LOCATION` a named `env` value set to
  `https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json`.
  Do **not** add `-ignore-missing-schemas`. The catalog covers `ServiceMonitor`, and without the
  flag a kind that has no schema fails the job rather than passing unchecked. Plain
  `kubeconform -strict` with only the default location fails on the `ServiceMonitor`
  (`could not find schema`).
  Fail if any of them fails and report every file before exiting, as `eval-charts.sh` does. A
  header comment states why `chart-lint.yml@v1` is not used (see Decisions).
- Update the reference in `docs/testing.md` row 20 (`build.yml` → `test.yml`, job names) and the
  `.github/workflows` entry in `AGENTS.md`.

Acceptance: `actionlint` passes locally. The `lint-chart` commands pass locally against both `ci/`
values files, and the `ServiceMonitor` of `ci/all-enabled-values.yaml` appears as valid in the
kubeconform summary, not as skipped. `grep -rn "build.yml"` finds no stale reference outside `docs/adr/`, because ADRs
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
    `github.event.pull_request.head.repo.full_name == github.repository && github.event.pull_request.user.login != 'dependabot[bot]'`.
    A fork PR has neither the Quay secrets nor a branch the tag could point at. A Dependabot PR
    gets no Actions secrets either, so its push would fail. The condition checks the PR author,
    not `github.actor`, so a human labelling a Dependabot PR does not trigger a push that would
    still lack the secrets. No `dependabot.yml` exists today, but the guard costs nothing.
    `generate-version` still runs for both, so the label is still required.

Acceptance: `actionlint` passes on all workflows. Locally,
`docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile .` succeeds after
`./mvnw versions:set -DnewVersion=0.0.1-PRE-1 -DgenerateBackupPoms=false`. Revert the pom
afterwards; it must not be committed. A single-platform `--load` build is enough when the local
builder cannot do multi-arch. The resulting image starts and its `/app/app.jar` comes from
`consent-manager-0.0.1-PRE-1.jar`.

### Step 3: Release workflow on push to main, gated on the tests
**Serialisation and its limit (accepted behaviour, state it in `docs/ci.md`).** A GitHub
concurrency group runs one job and keeps at most **one** pending. A newer pending run cancels the
older pending one, whatever `cancel-in-progress` says, so a concurrency group cannot queue
releases. The design therefore (a) holds the group only around the short publish section, not
the ~60-minute test run, so a supersede needs three merges whose tests finish within one image
build; and (b) makes a superseded or out-of-order run harmless:
- When a pending publish is superseded, GitHub cancels it. The newer run builds the newer `main`
  head, which contains the cancelled merge, so no change goes unreleased. **The cancelled PR's
  label bump is not applied**, though: the next release bumps the last tag by its own label only
  (`1.2.0` + a superseded `minor` + a `patch` gives `1.2.1`, not `1.3.1`). This is accepted, and
  `docs/ci.md` tells maintainers to let `Release` finish before merging the next PR when a
  `major`/`minor` bump must not be absorbed.
- Test runs finish in any order, so an older commit could reach publish after a newer one was
  released. The `check-superseded` guard below skips that run instead of tagging an older SHA
  with a higher version.

- New `.github/workflows/publish.yml` (`workflow_call`, no inputs, Quay secrets passed through),
  which holds everything that reads or writes release tags:
  - `check-superseded`: checkout with `fetch-depth: 0`. Find the highest plain `X.Y.Z` tag. Output
    `superseded=true` when that tag exists and
    `git merge-base --is-ancestor "$GITHUB_SHA" "$LATEST_TAG"` holds, meaning this commit, or a
    newer one containing it, is already released. The equal case also stops a re-run from
    releasing the same commit twice. Every later job carries
    `if: needs.check-superseded.outputs.superseded != 'true'`.
  - `generate-version`: the same `semver-version.yml@v1` call as in Step 2. On a push it resolves
    the merged PR's label and yields `X.Y.Z`. A commit pushed directly to `main` without a merged
    PR fails here, and FIWARE intends that.
  - `build-and-push`: `needs: [check-superseded, generate-version]`,
    `uses: ./.github/workflows/build-image.yml`.
  - `git-release`: `needs: [generate-version, build-and-push]` (Step 4 adds `chart-release`),
    `permissions: contents: write`. First fail if `gh release view "$VERSION"` succeeds, because a
    duplicate version is a bug and must not be silently overwritten. Then run
    `gh release create "$VERSION" --target "$GITHUB_SHA" --title "$VERSION" --generate-notes LICENSE`
    (not a pre-release). **The `X.Y.Z` tag is the last thing a publish run writes.**
    `check-superseded` reads it, so a run that tagged and then failed could not be re-run. A run
    that fails before tagging can be re-run whole: `build-and-push` pushes the same tag of the
    same commit again, and `git-release` creates the tag that is still missing.
- New `.github/workflows/release.yml`, `name: Release`, `on: push: branches: [main]`, top-level
  `permissions: contents: read` so the `test` caller job does not start from the repository's
  default token permissions. Only `publish` widens it. It has **no** workflow-level concurrency.
  Its jobs:
  - `test`: `uses: ./.github/workflows/test.yml`. Its own group is per run (Step 1), so concurrent
    pushes test in parallel and none is dropped. A release is never published from a commit that
    fails `verify`, the chart lint or actionlint. The reference does not gate; this improves on it.
  - `publish`: `needs: test`, `uses: ./.github/workflows/publish.yml`, `secrets: inherit` or the
    two Quay secrets explicitly. It sets job-level
    `concurrency: {group: release, cancel-in-progress: false}` (a caller job of a reusable workflow
    supports `concurrency`), so the whole publish workflow runs alone. It sets
    `permissions: {contents: write, pull-requests: write}`. That is the ceiling: the called jobs
    narrow it as in Step 2. The nesting is release → publish → build-image or semver-version,
    three levels, within GitHub's limit.
- In `test.yml`, drop the `push: branches: [main]` trigger, since the release now runs the suite on
  every push to `main` and the suite should not run twice. Keep `pull_request`,
  `workflow_dispatch` and `workflow_call`. Update `docs/testing.md` row 20 so it says the suite
  runs on every PR (`Test`) and on every push to `main` (inside `Release`).

Acceptance: `actionlint` passes. Every `needs:` edge and permission is checked by reading the
workflow graph: `release.yml` declares top-level `contents: read`, and `test` does not widen it.
Inside `publish.yml`, `contents: write` sits only on `git-release` and
`pull-requests: write` only on `generate-version`. `git-release` is the last job in the graph.
The `release` concurrency group sits on the
`publish` job only, and nothing else in the repository uses it. Tag `X.Y.Z` and image `X.Y.Z` come
from the same `generate-version` output. Check the `check-superseded` script locally in a scratch
clone with three commits A→B→C, tag B as `0.1.0`, and confirm that it outputs `true` for A and B,
`false` for C, and `false` when no tag exists.

### Step 4: Helm chart versioning and publication via the FIWARE chart workflows
**As merged (PR #40): reduced by review.** Chart publication moved to FIWARE/helm-charts, so
`chart-version`, `chart-release`, the `exclude` list and the squash-only/gh-pages prerequisites
were dropped. Only the `Chart.yaml`/`values.yaml`/chart README changes below (`appVersion: "0.1.0"`,
`quay.io/fiware/consent-manager`, GitHub `home`/`sources`) were kept. Every "if Step 4 is in" item of
Step 5 therefore does not apply.

Reuse `chart-pr.yml@v1` and `chart-release.yml@v1` so that the chart is released too. **The
reviewer may drop this step** if chart publication is meant to be a follow-up. Steps 1 to 3 do not
depend on it.

- **One exclude list, used twice.** `bump-chart-versions.sh` matches `exclude` against exact file
  paths (`case " $EXCLUDE " in *" $file "*`), so a directory such as `charts/consent-manager/ci/`
  excludes nothing. Name every file instead:
  `charts/consent-manager/README.md charts/consent-manager/ci/minimal-values.yaml charts/consent-manager/ci/all-enabled-values.yaml`.
  Pass the **identical** string to `chart-pr.yml` and to `chart-release.yml`. The latter's
  `check-versions` job fails a release when a chart file changed without a new `version`, so a file
  excluded from the bump but not from the check would make a README-only merge fail `Release` on
  `main`. `chart-release.yml@v1` does declare an `exclude` input (checked at `v1`; it feeds
  `check-chart-versions.sh`), so both workflows take the list. Each workflow carries a comment
  pointing at the other copy, and a new file under `ci/` must be added to both. `docs/ci.md`
  repeats that rule.
- `pre-release.yml`: add job `chart-version`:
  `uses: FIWARE/github-workflows/.github/workflows/chart-pr.yml@v1`,
  `permissions: {contents: write, pull-requests: write}`, `with: {exclude: <the list above>}`. It
  requires the same label and bumps `charts/consent-manager/Chart.yaml` `version` when the PR
  changes a non-excluded chart file, committing the bump to the PR branch.
  `charts/consent-manager/README.md` is not auto-generated, so do not set `helm-docs-version`.
- **The bump commit starts no checks (accepted, documented).** `chart-pr.yml` pushes with the
  workflow's `GITHUB_TOKEN`, and GitHub starts no workflow runs for pushes made with that token.
  So after a chart-touching PR is bumped, its new head commit has no `Test` or `Pre-Release` run.
  A required `Test` check then blocks the merge, and the pre-release from that run points at the
  pre-bump SHA. The chosen handling: the contributor **closes and reopens the PR** (or pushes
  again), which fires `reopened`/`synchronize` with the bumped head, so both workflows run on it.
  `chart-pr` sees that the version is already bumped and pushes nothing, which ends the cycle.
  Confirm that no-op in the first chart-touching PR. Alternatives considered and not taken: a PAT
  or GitHub App token would make the bump trigger runs, but it adds a long-lived write credential
  that only this job needs. Asking contributors to bump by hand would make chart-pr pointless.
  Making the checks non-required would let untested heads merge. `docs/ci.md` states the
  close/reopen step, and `chart-version` posts no extra comment (it cannot know whether checks are
  required).
- **Branches must be up to date before merging (repository setting, documented in Step 5).**
  `bump-chart-versions.sh` bumps from the base branch's version as it was when the job ran. Two
  chart-touching PRs opened against chart `0.1.0` both end at `0.2.0`, and the identical
  `version:` lines merge without a conflict. The second merge then changes templates while the
  version stays `0.2.0`, so `check-versions` fails `Release` on `main`. Branch protection on
  `main` must therefore enable "Require branches to be up to date before merging". Updating the
  second PR fires `synchronize`, `chart-pr` re-bumps from `0.2.0` to `0.3.0`, and the close/reopen
  step above applies again. If that failure still happens (the setting is off, or an admin
  bypasses it), the remedy is a follow-up PR that bumps the chart `version`. The failed run created
  no tag (see below), so that PR's `Release` releases both changes.
- `release.yml` → `publish.yml` (Step 3): add job `chart-release`,
  `needs: [check-superseded, build-and-push]`, with the same `superseded` condition:
  `uses: FIWARE/github-workflows/.github/workflows/chart-release.yml@v1`,
  `permissions: contents: write`, `with: {exclude: <the list above>}`. Placing it inside `publish.yml`
  and after `build-and-push` means three things. It runs only after the tests passed (`publish`
  needs `test`). It never publishes a chart for a run whose image push failed. And it is serialised
  by the same `release` group, so two runs cannot race chart-releaser. No OCI push by default.
  Leave the commented-out `oci-registry: quay.io/fiware/helm-charts` with the Quay secrets as the
  documented opt-in. Its GitHub releases are tagged `consent-manager-X.Y.Z`. `next-version.sh`
  ignores such tags because they are not plain `X.Y.Z`, so they do not affect the app versioning,
  and the Step 3 `check-superseded` guard looks only at plain `X.Y.Z` tags as well. Also raise the
  `publish` job's ceiling in `release.yml` if `chart-release` needs more than
  `contents: write`.
- **`git-release` runs after `chart-release`:** `needs: [generate-version, build-and-push, chart-release]`.
  This is required because chart-releaser-action v1.7.0 (`lookup_latest_tag` in `cr.sh`) picks
  the charts to release with `git diff $(git describe --tags --abbrev=0 HEAD~)`, and that sees
  **every** tag, including the app's `X.Y.Z`. If `git-release` tagged while `chart-release`
  failed, a full re-run would be skipped by `check-superseded`. The next merge's diff would also
  start at that tag, so the chart change would never be packaged (`skip_existing` cannot help
  with a chart that is never considered). With the tag written last, a failed `chart-release`
  leaves no tag and the run is re-run whole. On that re-run `chart-release` releases what is
  missing, and `skip_existing: true` (set by `chart-release.yml@v1`) skips what was already
  released. `chart-release` is a no-op when no chart changed, so this ordering costs a release
  nothing. Because no tag exists until the end, re-running all jobs and re-running only the
  failed ones are both safe.
- **Squash merges only (repository setting, documented in Step 5).** The same `git describe`
  also finds the `X.Y.Z-PRE-<pr>` tags that Step 2 puts on PR head commits. With merge commits, a
  merged PR's head is reachable from `HEAD~`. After a superseded publish (Step 3), the untagged
  `HEAD~` is closer to that PR's `-PRE-` tag than to the last release tag, so cr diffs from the
  PR head and misses that PR's chart change. With squash merges, PR heads are never ancestors of
  `main`, and `describe` only sees release tags on `main`. `semver-version.yml` still resolves a
  squash commit's PR through `commits/{sha}/pulls`. The repository therefore allows only squash
  merging.
- **`appVersion` is decoupled from the pom.** Today `Chart.yaml` carries
  `appVersion: "0.1.0-SNAPSHOT"` with the comment "Tracks <version> in pom.xml". CI never publishes
  that tag, so after this step the default image would not exist. Set `appVersion: "0.1.0"` and
  replace the comment: `appVersion` is the released image tag the chart pulls by default. It is set
  by hand in the PR that moves the chart to a new app release, and it never tracks `pom.xml`.
  `chart-pr.yml` bumps only `version`. `0.1.0` assumes that the merge of this ticket's work branch
  to `main` is the first labelled release and carries `minor`, which bumps `0.0.0` to `0.1.0`.
  Say so in the PR description and in `docs/ci.md`. Until that first release completes, the
  default tag does not resolve yet, and that is expected.
- `charts/consent-manager/values.yaml`: set the default `image.repository` to
  `quay.io/fiware/consent-manager`, matching `build-image.yml`. `Chart.yaml` `home`/`sources` point
  at `https://github.com/wistefan/dsc-consent-manager`. Mirror the `image.repository` change in
  `charts/consent-manager/README.md`'s values table.
- Prerequisite, documented in Step 5 and not automatable from the repo: an empty `gh-pages` branch
  and GitHub Pages served from it, which chart-releaser needs.

Acceptance: `actionlint` passes. The Step 1 `lint-chart` commands still pass. `helm template` with
`ci/minimal-values.yaml` renders `quay.io/fiware/consent-manager:0.1.0`, and
`grep -rn "0.1.0-SNAPSHOT" charts/` finds nothing. The `exclude` string is byte-identical in
`pre-release.yml` and `publish.yml` (check with `grep`), and it lists every file under
`charts/consent-manager/ci/`. In `publish.yml`, `git-release` needs `chart-release`, so the
plain `X.Y.Z` tag is still the last write of the run.

### Step 5: Document the CI/release process
- New `docs/ci.md`. For each workflow, give its trigger, jobs and what fails it. Describe the
  label convention (exactly one of `major`/`minor`/`patch` per PR, and the labels must exist in the
  repository) and the version scheme (`X.Y.Z-PRE-<pr>` on a PR, `X.Y.Z` on merge, first release
  bumped from `0.0.0`). List the required secrets (`QUAY_USERNAME`, `QUAY_PASSWORD`) and the
  repository settings: Actions allowed to create releases, the `gh-pages` branch and Pages if
  Step 4 is in, and, when Step 4 is in, squash merges only plus "Require branches to be up to
  date before merging" on `main` (Step 4 says why). Describe the fork and Dependabot PR behaviour:
  both get the label check but no image or pre-release. Explain how to change `REGISTRY`/`REPOSITORY`, and the
  deviations from the reference (Dockerfile instead of `-Poci`, `gh` instead of
  `marvinpinto`, local chart lint, no Coveralls). State the accepted limits as well. First, a
  superseded pending release is cancelled, and its label's bump is absorbed into the next release
  (Step 3). Let `Release` finish before merging the next PR when a `major`/`minor` must not be
  absorbed. Second, a failed `Release` that created no tag can be re-run, whether all jobs or
  only the failed ones, because the release tag is written last. If Step 4 is in, also document
  four things. A chart-bumped PR must be closed and reopened to get checks on its bumped head. A
  new file under `charts/consent-manager/ci/` must be added to both copies of the `exclude` list.
  `appVersion` is raised by hand and never tracks the pom. And if two chart bumps collide on
  `main` anyway, the remedy is a follow-up PR that bumps the chart `version`.
- `docs/README.md`: add a row under **Contributors** for the CI guide, in the same commit.
- `README.md`: add the `Test` and `Release` workflow status badges, and add a short
  "Releases / container image" line under the getting-started or contributing section that links
  to `docs/ci.md`.
- `docs/deployment.md` and `charts/consent-manager/README.md`: where they say how to obtain the
  image, point at `quay.io/fiware/consent-manager:<version>`.
- `AGENTS.md` (**deferred to a plan-mode update**: step agents must not edit `AGENTS.md`): list `.github/workflows/` (`test.yml`, `build-image.yml`, `pre-release.yml`,
  `release.yml`, `publish.yml`) in Project Structure. Add a Key Conventions bullet: a PR needs a semver label to
  pass the Pre-Release workflow, and the pom version is never committed by CI.

Acceptance: every relative link in the touched Markdown resolves (check with a link-check
one-liner or by hand). No ADR text is edited.
