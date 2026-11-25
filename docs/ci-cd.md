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

Each job has a `timeout-minutes` (30 for the Gradle jobs), so a build that thrashes fails
in half an hour instead of running to the 6-hour default.

### Runner memory

The compiling jobs run [`.github/actions/gradle-ci-memory`](../.github/actions/gradle-ci-memory/action.yml)
after `setup-gradle`. It writes `~/.gradle/gradle.properties` on the runner: Gradle daemon
5 GB, Kotlin daemon 4 GB, 3 workers. The repo's `gradle.properties` keeps 2 GB for local
machines; with that on CI, `:app:compileDebugKotlin` ran out of heap and Android Lint
(which runs in the Gradle daemon) spent most of its time in GC.

### Robolectric android-all jars

Robolectric's android-all jars are Gradle dependencies (`robolectricAndroidAll*` in
`app/build.gradle.kts`), so Gradle downloads them with retries, `setup-gradle` caches
them, and the tests read them through a generated `robolectric-deps.properties`.
Robolectric no longer downloads anything while tests run. When the jars aren't cached
yet (a PR before `main` has saved them, or after a Robolectric bump), the Unit tests job
fetches them in a separate step first and retries with backoff, because Maven Central
answers bursts of large downloads with `429 Too Many Requests`. The list has one entry per
SDK the tests use (every `@Config(sdk = …)` plus `targetSdk`). A test on an SDK that
isn't listed fails with `no artifacts found for org.robolectric:android-all-instrumented:…`:
add that SDK there. When Robolectric is bumped, the build stops with a message saying
to refresh the list from the new release.

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

Before signing off any `0.x.y` release across platforms, complete the mandatory **Documentation Sync** step in [`.agents/skills/release-docs-sync/SKILL.md`](../.agents/skills/release-docs-sync/SKILL.md) so Repo `docs/`, `README.md` / `README.zh-CN.md`, `.agents/skills/write-copy/references/01-yoin-facts-and-terms.md`, and `yoin-site` stay aligned with the release notes.

### 1. Android (isolated worktree signing → Google Play Closed Testing)

1. Create a clean release branch and isolated worktree off `main` (for example `release/0.6.3-28` in `../Yoin-release-0.6.3`) so uncommitted files in the primary checkout cannot leak into the build.
2. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, open a PR into `main`, and confirm the **`CI gate`** check is green.
3. Build the upload-key signed `.aab` and `.apk` locally using `~/.yoin/yoin-upload-keystore.properties` (or `YOIN_RELEASE_KEYSTORE_PROPERTIES`):

   ```bash
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   ./gradlew :app:bundleRelease :app:assembleRelease
   ```

   Verify `VERSION_CODE`, `VERSION_NAME`, and `NANO_TEST = false` on the output bundle before uploading.
4. Generate bilingual release notes (`<en-US>` and `<zh-CN>`, ≤ 500 characters per locale for Google Play) via Argon (`.argon/agy-yoin` with `write-copy`), then upload the `.aab` to Google Play **Closed testing** (Alpha).

> **GitHub Actions `Release` workflow & `RELEASE_KEYSTORE_*` secrets:** [`.github/workflows/release.yml`](../.github/workflows/release.yml) automates `.aab`/`.apk` signing, GitHub Release creation, and optional Play `internal`-track upload when a `v*` tag is pushed (`git tag v0.6.3-28 && git push origin v0.6.3-28`) or dispatched manually — **but only when all four `RELEASE_KEYSTORE_*` secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) are configured** under **Settings → Secrets and variables → Actions**. Until those secrets are populated on `p2o51/Yoin`, do not push `v*` tags expecting Actions to succeed (the workflow fails fast at secret validation); sign in the local isolated release worktree and upload artifacts manually via `gh release create` if a GitHub Release is needed.

### 2. iOS / iPadOS / macOS (`iosApp/` + `:shared` → TestFlight)

1. On the iOS line (`claude/project-thread-2amedi` until merged into `main`), update `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` in `iosApp/project.yml` and regenerate the Xcode project (`cd iosApp && xcodegen generate`).
2. Build the Release `:shared` framework (`./gradlew :shared:assembleYoinSharedReleaseXCFramework`), archive the `Yoin` scheme in Xcode (**Product → Archive**), and distribute via **TestFlight** (covering iPhone, iPad, and iPad-on-Mac multiplatform builds).

### 3. HarmonyOS (`harmony/`)

1. Source `~/Developer/flutter_ohos/ohos-env.sh` and verify `flutter analyze && flutter test` are green.
2. Build the signed release HAP (`flutter build hap --release`), smoke-test on target HarmonyOS devices (`hdc -t <device-id> install -r build/ohos/hap/entry-default-signed.hap`), and ensure `harmony/docs/` matches the shipped behavior.

### 4. Mandatory Repo & `yoin-site` Documentation Sync

Alongside Argon's bilingual release notes:

- **Repository**: verify `docs/` (`design.md`, `platforms.md`, `integrations/provider-alignment.md`, `cloud-sync.md`, `playlist-transfer.md`, `i18n/zh-glossary.md`, plus `harmony/docs/` and `iosApp/docs/`), `README.md` & `README.zh-CN.md`, and `.agents/skills/write-copy/references/01-yoin-facts-and-terms.md` reflect all shipped changes.
- **Prototypes and site visuals**: before each release, update the prototypes of the screens that changed (`design/hifi`), then export the site visuals with `export.mjs` (`node design/hifi/export.mjs --batch design/hifi/site/<version>.json --out <yoin-site worktree>/public/media/changelog/<version>`); never hand-draw app UI for the site. See [`design/hifi/README.md`](../design/hifi/README.md), "How to update before a release".
- **`yoin-site`**: in an isolated `yoin-site` worktree, update `public/changelog.html` & `public/zh/changelog.html` (including the `spot.js` before/after figure, built from the exported frames), `public/index.html` & `public/zh/index.html` (version chips and platform badges), `public/licenses.html` & `public/zh/licenses.html`, `public/docs.html` & `public/zh/docs.html`, and `public/privacy.html` (whenever data flows, SDKs, or AI/key storage change). Run `python3 tools/check.py`, merge the `yoin-site` PR, and deploy via `npx wrangler@4 deploy`.

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
| `CI_ASSETS_TOKEN` | yes (CI + Release) | fine-grained token, **Contents: read-only** on `p2o51/yoin-ci-assets`; lets CI download the Apple MusicKit AARs |

### Apple MusicKit AARs

Apple's Android MusicKit SDK (`app/libs/musickitauth-release-1.1.2.aar`,
`app/libs/mediaplayback-release-1.1.1.aar`) may not be redistributed, so the
files are gitignored. CI and Release fetch them from the
`apple-musickit-1.1.2` release of the **private** repository
`p2o51/yoin-ci-assets` through [`.github/actions/apple-musickit`](../.github/actions/apple-musickit/action.yml),
which checks their SHA-256 against [`app/libs/README-apple-music.md`](../app/libs/README-apple-music.md).

- `CI_ASSETS_TOKEN` expires (fine-grained tokens last at most a year). When it
  does, every CI job that builds the app fails at "Fetch Apple MusicKit SDK" with
  a "may have expired" error; create a new token with the same scope and replace
  the secret.
- Fork PRs and Dependabot PRs don't receive repository secrets. The `MusicKit
  access` job notices this, and CI then runs only ktlint: unit tests, Android
  Lint and the debug build are skipped with a warning in the run summary. On a
  push or a same-repo PR, a missing token fails the run instead. To have
  Dependabot PRs fully checked, add the same token under **Secrets and variables →
  Dependabot**.
- Upgrading the SDK: upload the new AARs as a new release in `yoin-ci-assets`,
  then update the tag and the sums in the action, `app/build.gradle.kts` and the README.

Unit tests cache Robolectric's android-all jars (`~/.m2/repository/org/robolectric`),
keyed on the Robolectric version in `gradle/libs.versions.toml`. Robolectric
downloads them from Maven Central on first use, and an uncached download
occasionally fails mid-run.

Yoin Symbols is checked out from `p2o51/yoin-symbols` at `main` (`ref: main` in both
workflows), so a merged icon change reaches the next build with nothing to bump.

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
