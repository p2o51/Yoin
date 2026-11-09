# Round 3: why the close from Full jumps

All at animator_duration_scale 5 on emulator yoin_tips_qa.

- s1-640-5b0e-slowmo: 5b0e9a0d (before the latch), 640dp Enlarged, open + back. The close morph animates.
- s1b-800-5b0e-album-slowmo: 5b0e9a0d, 800dp, album page: pill -> panel -> full -> chevron close. The close morph animates.
- r3-800-fix-slowmo: 99273ce9 + fix.diff, 800dp Home: pill -> panel -> full -> panel -> full -> chevron close. Toggles show no morph; the close shrinks the cover into the pill.
- r3-1280-fix-slowmo: same fix, 1280dp two-column: pill -> panel -> full -> chevron close.
- r3-640-fix-slowmo: same fix, 640dp: open + back.
- r3-portrait-fix-slowmo: same fix, portrait phone: open + back (regression check).

Cause: after panel -> full, NP's end of the morph stays latched at None (latched at the panel open).
It switches to CoverAndText only in the same frame the exit starts, so the cover's sharedBounds gets
no prior bounds and lands in the pill at once. fix.diff makes NP's end follow the shown presentation;
the bar's end keeps the latch.
