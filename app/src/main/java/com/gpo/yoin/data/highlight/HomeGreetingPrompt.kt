package com.gpo.yoin.data.highlight

import com.gpo.yoin.shared.ondevice.HomeGreetingPrompt
import java.time.DayOfWeek
import java.time.Month

/*
 * Home's 3-slot greeting prompt and validator live in :shared (`com.gpo.yoin.shared.ondevice`, parity p5,
 * 2026-11-27) so iOS asks Apple Intelligence the same words; the slot and the day's row keep their names here,
 * the app imports `com.gpo.yoin.shared.ondevice.HomeGreetingPrompt` itself (a typealias can't reach its nested
 * RecentPlay / SlotCues). The shared code takes ISO day / month numbers, so the `java.time` overloads Android has
 * always called are kept below.
 * OnDeviceAiPinTest proves every golden vector answers as the pre-move code did.
 */

typealias HomeGreetingSlot = com.gpo.yoin.shared.ondevice.HomeGreetingSlot
typealias DailyHomeGreetings = com.gpo.yoin.shared.ondevice.DailyHomeGreetings

fun HomeGreetingPrompt.userPrompt(
    cues: HomeGreetingPrompt.SlotCues,
    dayOfWeek: DayOfWeek,
    month: Month? = null,
    localeTag: String,
    avoidRecent: Collection<String> = emptyList(),
): String = userPrompt(cues, dayOfWeek.value, month?.value, localeTag, avoidRecent)

fun HomeGreetingPrompt.userPrompt(
    recentPlays: List<HomeGreetingPrompt.RecentPlay>,
    dayOfWeek: DayOfWeek,
    month: Month? = null,
    localeTag: String,
): String = userPrompt(recentPlays, dayOfWeek.value, month?.value, localeTag)

fun HomeGreetingPrompt.buildPrompt(
    cues: HomeGreetingPrompt.SlotCues,
    dayOfWeek: DayOfWeek,
    month: Month? = null,
    localeTag: String,
    avoidRecent: Collection<String> = emptyList(),
): String = buildPrompt(cues, dayOfWeek.value, month?.value, localeTag, avoidRecent)

fun HomeGreetingPrompt.buildPrompt(
    recentArtists: List<String>,
    dayOfWeek: DayOfWeek,
    localeTag: String,
    month: Month? = null,
): String = buildPrompt(recentArtists, dayOfWeek.value, localeTag, month?.value)
