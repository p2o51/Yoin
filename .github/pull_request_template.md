<!-- Keep the title in Conventional Commits style, e.g. feat(detail): ... / fix(np): ... -->

## Summary

<!-- What does this change and why? Link any related issue (Closes #123). -->

## Type of change

- [ ] feat — new user-facing capability
- [ ] fix — bug fix
- [ ] refactor / chore — no behaviour change
- [ ] docs / ci

## How was this tested?

<!-- Unit tests, manual device/emulator runs, screenshots for UI work. -->

## Checklist

- [ ] `./gradlew ktlintCheck` passes locally (and `flutter analyze && flutter test` / Xcode tests when touching `harmony/` or `iosApp/`)
- [ ] `./gradlew test` passes locally
- [ ] UI changes include before/after screenshots or a screen recording
- [ ] User-facing changes, provider behavior, settings, shortcuts, or limits update the corresponding `docs/` spec (plus `harmony/docs/` or `iosApp/docs/` if platform-specific), and single-platform rollouts log the gap in `docs/platforms.md`
- [ ] New or changed gestures, shortcuts, and hidden entry points update in-app Tips (`HomeBubbleTip` / `ui/tips/`) in the same PR
- [ ] Release PRs (`release/*`) complete the Documentation Sync checklist (`.agents/skills/release-docs-sync/SKILL.md`) before sign-off
- [ ] No secrets, keystores, or tokens committed
