# Contributing to Yoin

Thanks for helping out! This is a short guide to the workflow. For the full CI/CD
details see [docs/ci-cd.md](docs/ci-cd.md).

## Workflow

1. **Branch off `main`.** Use a descriptive prefix: `feat/…`, `fix/…`, `refactor/…`,
   `chore/…`, `docs/…`.
2. **Make the change** and keep commits in
   [Conventional Commits](https://www.conventionalcommits.org/) style
   (`feat(detail): …`, `fix(np): …`), matching the existing history.
3. **Update Tips and documentation in the same PR.**
   - **Tips:** New gestures, shortcuts, or hidden entry points require an in-app Tip update (`HomeBubbleTip` in `ui/home/HomeBubbleSay.kt`, demos in `ui/tips/`, and platform counterparts; tip copy comes from Argon via `.argon/agy-yoin`).
   - **Docs sync:** Any PR adding or changing user-facing features, provider behavior, settings, shortcuts, or limits must update the matching `docs/` spec (`docs/design.md`, `docs/platforms.md`, `docs/integrations/provider-alignment.md`, `docs/cloud-sync.md`, `docs/playlist-transfer.md`, `docs/i18n/zh-glossary.md`, plus `harmony/docs/` or `iosApp/docs/` when platform-specific). If a feature ships on one platform first, log the parity gap in `docs/platforms.md`.
   - **Releases (`0.x.y`):** Every release must complete the Documentation Sync checklist in [`.agents/skills/release-docs-sync/SKILL.md`](.agents/skills/release-docs-sync/SKILL.md) before sign-off.
4. **Run the checks locally** (see below) before opening a PR.
5. **Open a pull request** into `main`. The PR template walks through summary,
   testing, and the checklist.
6. **CI must be green** and conversations resolved before merging. You can merge
   your own PR once the `CI gate` check passes.

Direct pushes to `main` are blocked by branch protection.

## Running checks locally

Use Android Studio's bundled JBR for command-line builds:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew ktlintCheck     # style — CI gate
./gradlew ktlintFormat     # auto-fix style
./gradlew test             # unit tests — CI gate
./gradlew :app:lintDebug   # Android Lint (baseline) — CI gate
./gradlew assembleDebug    # debug build — CI gate
```

These four are exactly what CI enforces, so a clean local run means a green PR.
If your PR touches `harmony/` or `iosApp/` / `:shared`, also run the platform checks documented in [`harmony/README.md`](harmony/README.md) (`flutter analyze && flutter test`) and `iosApp/README.md` / `iosApp/docs/shared-kmp.md`.

## Don't commit secrets

Keystores (`*.jks`/`*.keystore`), `local.properties`, and API tokens are
git-ignored — keep it that way. Release signing and Play uploads run from GitHub
Actions secrets (see [docs/ci-cd.md](docs/ci-cd.md)).
