# Subpage NP morph recordings

Branch under test: `claude/project-thread-8hbscf` @ 153cd92d; comparison: `main` @ fca8ce60.
Device: emulator `yoin_tips_qa` (1080x2400 @420, API 36), muted, fake Subsonic server.

- a1–a4: pill → NP → swipe down, 3x from album / artist / playlist pages; album → artist nested once.
- b1/b2: animator_duration_scale 5, one open+close from an album page (branch vs main).
- c1–c4: regression checks (mp4 + png each).
- e1: tablet-size display override (2560x1600 @320) — NP as side panel.
- jank-gfxinfo.txt, quick-swipe-trials-*.txt: raw numbers.
