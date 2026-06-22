# CI/CD

Yoin uses GitHub Actions for continuous integration and release automation.

## Workflows

| Workflow | File | Trigger | What it does |
| --- | --- | --- | --- |
| **CI** | [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) | push to `main`, every PR, manual | ktlint, unit tests, Android Lint, assemble debug APK |
| **Release** | [`.github/workflows/release.yml`](../.github/workflows/release.yml) | tag `v*`, manual | build signed AAB + APK, create a GitHub Release, optional Google Play internal-track upload |

### CI jobs

CI runs four jobs in parallel and a final **`CI gate`** job that aggregates them.
The gate is the single status check enforced by branch protection, so individual
jobs can be renamed or added without reconfiguring the protected branch.

- **ktlint** — `./gradlew ktlintCheck`
- **Unit tests** — `./gradlew test` (app JVM/Robolectric tests + the playground module)
- **Android Lint** — `./gradlew :app:lintDebug`, gated against `app/lint-baseline.xml`
- **Assemble debug APK** — `./gradlew assembleDebug` (APK uploaded as a build artifact)

Reports (ktlint, test, lint) and the debug APK are uploaded as artifacts on every run.

### Android Lint baseline

Lint runs against [`app/lint-baseline.xml`](../app/lint-baseline.xml). Pre-existing
issues are recorded there so CI only fails on **new** lint errors. After
intentionally fixing existing issues, refresh the baseline:

```bash
./gradlew :app:updateLintBaseline
```

## Branch protection (`main`)

- Pull request required before merging — no direct pushes to `main`.
- The `CI gate` status check must pass, and the branch must be up to date.
- Conversations must be resolved before merge.
- Approving reviews are **not** required (solo-friendly self-merge once CI is green).
- Force pushes and branch deletion are blocked.

## Cutting a release

```bash
git tag v0.5.1
git push origin v0.5.1
```

This builds a signed AAB + APK and publishes a GitHub Release. If the Play
service-account secret is configured, the AAB is also promoted to the Play
**internal** track.

### Required repository secrets

Add these under **Settings → Secrets and variables → Actions**. Until the signing
secrets exist, the Release workflow fails fast with a clear message.

| Secret | Required | Purpose |
| --- | --- | --- |
| `RELEASE_KEYSTORE_BASE64` | yes | `base64 -i upload-keystore.jks` — the upload keystore |
| `RELEASE_KEYSTORE_PASSWORD` | yes | keystore store password |
| `RELEASE_KEY_ALIAS` | yes | signing key alias |
| `RELEASE_KEY_PASSWORD` | yes | signing key password |
| `SPOTIFY_CLIENT_ID` | optional | baked into the release build config |
| `PLAY_SERVICE_ACCOUNT_JSON` | optional | Google Play service-account JSON; presence enables the Play upload step |

Generate the keystore base64 locally:

```bash
base64 -i ~/.yoin/upload-keystore.jks | pbcopy   # macOS
```

The release build reuses the same signing wiring as local release builds — see
[`docs/release-0.5-closed-test.md`](release-0.5-closed-test.md) for upload-key setup.

> Google Play uploads use [`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play).
> The very first upload of a new app/version must be done manually in the Play
> Console; the action handles subsequent internal-track uploads.

## Dependency updates

[Dependabot](../.github/dependabot.yml) opens grouped weekly PRs for Gradle
dependencies (via the version catalog) and GitHub Actions. Those PRs run through
the same CI gate before they can be merged.
