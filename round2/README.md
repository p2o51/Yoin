# Round 2: landscape + tablet NP morph (99273ce9)

Emulator yoin_tips_qa (1080x2400, API 36), muted, zh-CN, fake Subsonic.
Tablet windows simulated with `wm size` + `wm density 320`.

- a-landscape-{home,album}: landscape phone, pill -> NP -> back, 2x each
- a-landscape-home-slowmo: same at animator_duration_scale 5
- b-medium-{home,album}: 800dp: pill -> panel -> full -> panel -> full -> chevron close; pill -> panel -> full -> swipe-down close
- b-medium-home-slowmo: pill -> panel -> full -> panel -> full -> chevron close at 5x
- c-wide-{home,album}: 1280dp, same sequence (close by chevron; swipe-down does not dismiss two-column full)
- c-wide-home-slowmo: 5x
- d-640-{home,album}: 640dp no-panel: pill -> enlarged phone -> back; pill -> swipe-down
- e-rotate-np-open: landscape NP open, rotate 0/1/0 (recording keeps the first orientation's frame)
- e-wmsize-np-open / e-wmsize-panel-open: wm size Medium<->Wide x4 with NP full / panel open
- f-portrait-{home,album}: portrait phone sanity
