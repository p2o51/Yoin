# Motion Audit Matrix

Yoin now treats Material 3 motion theming as the baseline.

- `Expressive` is reserved for prominent hero surfaces and scene transitions.
- `Standard` is used for utilitarian, recurring, or control-heavy interactions.
- `AdaptiveReduced` keeps the same scene role but shifts to lighter buckets and lower motion amplitude.

| Surface | Trigger | Role | Property class | Speed bucket | Reduced-motion behavior |
| --- | --- | --- | --- | --- | --- |
| Shell section switch | Home / Library toggle | Standard | Effects | Default | Keep crossfade only; no added spatial flourish |
| Now Playing overlay | Open / dismiss full player | Expressive shell motion + standard support fade | Spatial + effects | Default | Keep directional slide but shorten travel and settle faster |
| Bottom nav group | Section selection / playback summary updates | Standard | Spatial + effects | Default | Preserve continuity; avoid bounce escalation |
| Home hero/editorial | First reveal, cover morph, memories pull affordance | Expressive | Spatial + effects | Slow for hero, default for support | Keep hierarchy, reduce amplitude and delay |
| Home repeated rows | Horizontal scroll / row reuse | Standard | None on item re-entry | N/A | No per-item entrance on scroll |
| Memories deck | Edge advance, deck swap, indicator movement | Expressive | Spatial | Slow | Keep directional continuity with faster settle |
| Memories support text | Date, metadata, list restoration | Standard | Effects + spatial | Default | Keep subtle fade/offset only |
| Now Playing hero | Cover, title, artist shared morph and stretch | Expressive | Spatial | Slow | Preserve shared continuity; reduce stretch intensity |
| Now Playing controls | Play, skip, favorite, queue, cast, pills | Standard | Spatial + effects | Default / Fast | Favor tighter width and color changes; no hero bounce |
| Album detail | Shared cover transition + staged content reveal | Expressive hero + standard support | Spatial + effects | Slow hero, default support | Shared artwork still leads; supporting content stays softer |
| Detail page open | Launch Album / Artist / Playlist | Expressive scene motion | Spatial + effects | Default | Keep the content slide, shorten its offset; the window still only fades |
| Detail predictive back | Edge swipe or back button on a detail page | Expressive scene motion | Spatial + effects | Default, gesture-scrubbed | Keep the AOSP collapse at lower amplitude; settle sooner after commit |
| Shell behind a detail | Detail hand-off, back gesture, commit settle | Expressive | Spatial | Default | Hold the recede but shrink its offset; drop the vertical follow |
| Bottom bar chrome | Detail launch tap, back scrub, commit settle | Standard | Spatial | Default | Morph straight between nav and split; no interactive scrub preview |
| Bottom bar idle | Nothing playing, first track arriving | Standard | Spatial + effects | Default | Width morph only; labels fade without added travel |
| Now Playing pill | Track change from skip, auto-advance, or remote device | Standard chrome + expressive push-in | Spatial + effects | Fast out, default in | Swap track content with a fade; no horizontal push |
| Library | Tab content swap, search state, repeated content reveal | Standard | Effects | Default | Crossfade only; no expressive stagger on repeat visits |
| Settings | Connection status, form visibility, save/test feedback | Standard | Effects | Default | Minimal color/alpha feedback, no theatrical movement |

## Review Checklist

- Prominent scene changes use `YoinMotionRole.Expressive`.
- Utilitarian controls and repeated transitions use `YoinMotionRole.Standard`.
- Shared bounds and route transitions read from `YoinMotion`, not raw `spring/fade/slide`.
- Supporting content enters softer than hero content and exits at least as gently.
- Repeated list scrolling does not trigger fresh performative entrance animations.
- `AdaptiveReduced` swaps to lighter buckets without changing scene role.
- Cross-window poses (detail collapse, shell recede, bar morph) have exactly one owner per window; a second driver on the same value reads as a flash at the hand-off.
