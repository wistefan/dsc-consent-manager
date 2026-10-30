# Continuous integration and releases

[README](../README.md) › [Documentation](README.md) › CI and releases

GitHub Actions tests every pull request. A pull request into `main` that carries a semver label
also publishes a pre-release, and every merge to `main` publishes a release. The workflows live in
[`.github/workflows/`](../.github/workflows/). They are modelled on
[FIWARE/contract-management](https://github.com/FIWARE/contract-management/tree/main/.github/workflows),
and versions are worked out by
[`FIWARE/github-workflows` `semver-version.yml@v1`](https://github.com/FIWARE/github-workflows).

| Event | What runs | What it publishes |
| --- | --- | --- |
| Pull request (any base branch) | `Test` | nothing |
| Pull request into `main` | `Test`, `Pre-Release` | image and GitHub pre-release `X.Y.Z-PRE-<pr>` |
| Push to `main` (a merged PR) | `Release` (runs `Test`, then `Publish`) | image and GitHub release `X.Y.Z` |
| Manual (`workflow_dispatch`) | `Test` | nothing |

## Workflows

### `Test` ([`test.yml`](../.github/workflows/test.yml))

Triggers: `pull_request` (default types only, so a label change does not re-run the suite),
`workflow_dispatch`, and `workflow_call` from `Release`. It has no `push` trigger, because
`Release` runs it on every push to `main` and the suite should not run twice. A new push to a PR
cancels that PR's stale run. Every other run gets a concurrency group of its own, so none is
queued or dropped.

| Job | Fails when |
| --- | --- |
| `verify` | `./mvnw -B -ntp verify` fails: compilation, Spotless `check`, a unit or integration test, either JaCoCo gate, the Spectral lint (`SpecLintIT`) or the oasdiff breaking-change check (`SpecCompatibilityIT`). It also fails when either spec IT did not run or skipped a case. It uploads `jacoco-report` every time, and the Surefire/Failsafe reports when it fails. |
| `lint-workflows` | [actionlint](https://github.com/rhysd/actionlint) (pinned by `ACTIONLINT_VERSION`) reports a problem in any workflow. |
| `lint-chart` | `helm lint --strict` fails on `charts/consent-manager`, alone or with any `ci/*-values.yaml`, or `kubeconform -strict` rejects what the chart renders with those values. A kind with no schema fails too. Every values file is checked before the job reports. |

### `Pre-Release` ([`pre-release.yml`](../.github/workflows/pre-release.yml))

Triggers: `pull_request` into `main`, types `opened`, `synchronize`, `reopened`, `labeled` and
`unlabeled`. Adding or removing a label other than `major`/`minor`/`patch` skips every job. A new
run cancels the PR's previous run.

| Job | Does | Fails when |
| --- | --- | --- |
| `generate-version` | Calls `semver-version.yml@v1` to compute `X.Y.Z-PRE-<pr>`. | The PR does not carry exactly one semver label. It then comments on the PR asking for one. |
| `build-and-push` | Calls [`build-image.yml`](../.github/workflows/build-image.yml) on the PR head commit. | The Maven package or the multi-arch image build fails, or the registry login or push fails. |
| `git-release` | Deletes every earlier `X.Y.Z-PRE-<pr>` release and tag of this PR, then creates the GitHub pre-release `X.Y.Z-PRE-<pr>` on the PR head, with `LICENSE` attached and the image reference in the notes. | The GitHub API refuses the release. |

Each push therefore replaces the PR's pre-release and moves its tag to the new head. Changing the
label also removes the pre-release of the old version.

Make the check named **`generate-version / Semver version`** required on `main`. That is the
label gate. The job that is skipped on an ignored label event reports as plain `generate-version`,
so it cannot satisfy the requirement.

### `Release` ([`release.yml`](../.github/workflows/release.yml)) and `Publish` ([`publish.yml`](../.github/workflows/publish.yml))

Trigger: `push` to `main`. `Release` has two jobs:

1. `test` calls `Test` in full, so a commit that fails the suite, actionlint or the chart lint is
   never released.
2. `publish` calls `Publish` under the concurrency group `release`, so only one publish runs at a
   time. Only this job is serialised, not the hour-long test run.

`Publish` jobs, in order:

| Job | Does | Fails when |
| --- | --- | --- |
| `check-superseded` | Skips the rest of the run when the latest `X.Y.Z` tag already contains the pushed commit. That happens when an older commit's tests finish after a newer commit was released, or when a released run is re-run. | Never on its own. |
| `generate-version` | Calls `semver-version.yml@v1` to compute `X.Y.Z` from the label of the PR the commit was merged from. | No merged PR contains the commit, as with a direct push to `main`, or that PR does not carry exactly one semver label. |
| `build-and-push` | Calls `build-image.yml` on the pushed commit. | As for `Pre-Release`. |
| `git-release` | Creates the GitHub release `X.Y.Z` on the pushed commit, with `LICENSE`, the image reference and notes generated since the previous `X.Y.Z` tag. | A release or tag `X.Y.Z` already exists. Neither is ever overwritten. |

The `X.Y.Z` tag is the last thing a run writes. The image tag and the git tag come from the same
`generate-version` output.

### `Build image` ([`build-image.yml`](../.github/workflows/build-image.yml))

This is a reusable workflow only, shared by both release paths so they cannot drift. It sets the
project version in its own checkout with `./mvnw versions:set` (never committed), then builds
[`docker/Dockerfile`](../docker/Dockerfile) for `linux/amd64,linux/arm64` with QEMU and Buildx. It
pushes `quay.io/fiware/consent-manager:<version>` with the OCI labels `version`, `revision` and
`source`, and caches layers in the GitHub Actions cache. The Dockerfile's build stage runs on the
builder's own platform, because the jar is architecture-independent. Only the runtime stage is
emulated for `arm64`.

## Labels and versions

- **Exactly one** of `major`, `minor` or `patch` on every PR into `main`. Without one,
  `Pre-Release` fails and asks for it, and the merge would fail `Release`. Create the three
  labels in the repository once, because GitHub does not create them:

  ```bash
  gh label create major && gh label create minor && gh label create patch
  ```

- **Base version.** The highest plain `X.Y.Z` tag (the tag prefix is empty, so `1.2.3`, not
  `v1.2.3`), or `0.0.0` when there is none. The first release is therefore `0.1.0` with `minor`,
  `0.0.1` with `patch`, or `1.0.0` with `major`.
- **On a PR:** `X.Y.Z-PRE-<pr number>`, the base bumped by the label. The tag and pre-release
  persist after the PR is merged or closed.
- **On merge:** `X.Y.Z`, the base bumped by the merged PR's label.
- **The pom version is never committed by CI.** `pom.xml` stays `0.1.0-SNAPSHOT` in git. The build
  sets the release version only in its own checkout, so the packaged jar is
  `consent-manager-<version>.jar`.
- PRs into any other branch, such as stacked or per-step PRs, run `Test` only. They need no label
  and publish nothing.

## Required secrets and repository settings

| Setting | Where | Why |
| --- | --- | --- |
| `QUAY_USERNAME`, `QUAY_PASSWORD` | Settings › Secrets and variables › Actions | A Quay robot account with write access to `quay.io/fiware/consent-manager`. `GITHUB_TOKEN` covers the GitHub releases, and nothing else is needed. |
| Labels `major`, `minor`, `patch` | Issues › Labels | The version bump (above). |
| Actions may run `FIWARE/github-workflows`, `docker/*` and `azure/setup-helm` | Settings › Actions › General › Actions permissions | Only needed when the repository or organisation restricts which actions may run. |
| `GITHUB_TOKEN` may be granted `contents: write` and `pull-requests: write` | Settings › Actions › General › Workflow permissions | Each job requests only what it needs (`contents: write` to create releases and tags, `pull-requests: write` to ask for the label). The default "read" setting is fine as long as the organisation does not cap these. |
| Branch protection on `main` | Settings › Branches | Require pull requests, so that every commit on `main` has a PR and a label. Require the `Test` checks (`verify`, `lint-workflows`, `lint-chart`) and `generate-version / Semver version`. |

## Fork and Dependabot pull requests

Both get `Test` and `generate-version`, so they still need a label. Neither gets an image or a
pre-release. A fork PR has no Actions secrets and no branch the tag could point at, and a
Dependabot PR gets no Actions secrets either. The check looks at the PR **author**, not at whoever
triggered the run, so a maintainer labelling a Dependabot PR does not trigger a push that would
fail. These PRs receive a read-only token, so the "please add a label" comment may not be posted.
The failed check still says what is missing.

## Changing the registry or image name

The registry and namespace are `env` values in
[`build-image.yml`](../.github/workflows/build-image.yml): `REGISTRY` (`quay.io`), `REPOSITORY`
(`fiware`) and `IMAGE_NAME` (`consent-manager`). The login uses `REGISTRY`, so all three change in
that one file. Two more edits go with them:

- The secrets are passed as `QUAY_USERNAME`/`QUAY_PASSWORD` by `pre-release.yml` and `release.yml`
  (and declared in `publish.yml`). Rename them there if the new registry uses other credentials.
- Point `image.repository` in
  [`charts/consent-manager/values.yaml`](../charts/consent-manager/values.yaml) and its row in the
  [chart README](../charts/consent-manager/README.md#values) at the new image.

## Helm chart

CI lints the chart but does **not** publish it. Chart publication moves to
[FIWARE/helm-charts](https://github.com/FIWARE/helm-charts). Both fields of
[`Chart.yaml`](../charts/consent-manager/Chart.yaml) are maintained by hand:

- `version` is the chart's own version.
- `appVersion` is the released image tag the chart pulls by default. It is raised by hand when
  the chart should move to a new release, and it never tracks `pom.xml`. It is `0.1.0` today. That
  assumes the first labelled release is a `minor` bump from `0.0.0`. If the first release
  carries another label, change `appVersion` to match. Until the first release completes, the
  default image tag does not exist yet.

## Deviations from the reference

| Reference (`contract-management`) | Here | Why |
| --- | --- | --- |
| `mvn deploy -Poci` builds the image | `docker/build-push-action` with `docker/Dockerfile` | The repository has one image definition, the Dockerfile, and no `oci` profile. |
| `marvinpinto/action-automatic-releases` | `gh release create` | That action is archived and runs on a deprecated Node runtime. `gh` gives the same release, title and `LICENSE` asset. |
| `chart-lint.yml@v1` | the local `lint-chart` job | The reusable workflow renders the chart with its default values, which deliberately refuse to render without the required inputs. The same tools run here against `ci/*-values.yaml`. |
| Coveralls upload | none | The JaCoCo gates in `./mvnw verify` already enforce coverage. The HTML report stays an artifact. |
| Release not gated on tests | `Release` runs `Test` first | A release is never published from a commit that fails the suite. |

## Accepted limits

- **A superseded pending publish absorbs its label.** A concurrency group keeps at most one
  pending run, and a newer one cancels it. If a third merge's tests finish while a publish is
  running and another is pending, the pending one is cancelled. The newer run releases the newer
  `main` head, which contains the cancelled merge, so no change goes unreleased. But it bumps the
  last tag by **its own** label only: `1.2.0`, then a cancelled `minor`, then a `patch` gives
  `1.2.1`, not `1.3.1`. When a `major` or `minor` bump must not be absorbed, let `Release` finish
  before merging the next PR.
- **Re-running a failed `Release`.** Because the `X.Y.Z` tag is written last, a run that failed
  before creating it can be re-run, either all jobs or only the failed ones. A re-run of a run that
  did create the tag is skipped by `check-superseded`.
- **Out-of-order test runs.** An older commit whose tests finish after a newer commit was released
  is skipped by `check-superseded` rather than tagged with a higher version. Its changes are
  already part of the newer release.
- **Pre-releases are not cleaned up** when a PR is merged or closed. Their tags and image tags
  remain until removed by hand.

## Validating workflow changes locally

```bash
bash <(curl -sSfL https://raw.githubusercontent.com/rhysd/actionlint/main/scripts/download-actionlint.bash)
./actionlint -color
```

The publish paths run only on GitHub. To check an image build locally, set a version in a
throwaway checkout and build one platform:

```bash
./mvnw versions:set -DnewVersion=0.0.1-PRE-1 -DgenerateBackupPoms=false
docker buildx build --platform linux/amd64 --load -f docker/Dockerfile -t consent-manager:local .
git checkout pom.xml
```
