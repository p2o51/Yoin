# Contributing to Yoin

Thanks for helping out! This is a short guide to the workflow. For the full CI/CD
details see [docs/ci-cd.md](docs/ci-cd.md).

## Workflow

1. **Branch off `main`.** Use a descriptive prefix: `feat/…`, `fix/…`, `refactor/…`,
   `chore/…`, `docs/…`.
2. **Make the change** and keep commits in
   [Conventional Commits](https://www.conventionalcommits.org/) style
   (`feat(detail): …`, `fix(np): …`), matching the existing history.
3. **Run the checks locally** (see below) before opening a PR.
4. **Open a pull request** into `main`. The PR template walks through summary,
   testing, and the checklist.
5. **CI must be green** and conversations resolved before merging. You can merge
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

## Don't commit secrets

Keystores (`*.jks`/`*.keystore`), `local.properties`, and API tokens are
git-ignored — keep it that way. Release signing and Play uploads run from GitHub
Actions secrets (see [docs/ci-cd.md](docs/ci-cd.md)).
