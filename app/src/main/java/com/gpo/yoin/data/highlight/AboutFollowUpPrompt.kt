package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.shared.ondevice.AboutFollowUpEntry
import com.gpo.yoin.shared.ondevice.AboutFollowUpPrompt

/*
 * The About follow-up prompt and validator live in :shared (`com.gpo.yoin.shared.ondevice`, parity p5,
 * 2026-11-27) so iOS asks Apple Intelligence the same words; the app imports it from there, and the Room rows are
 * mapped to the shared entry below. OnDeviceAiPinTest proves every golden vector answers as the pre-move
 * code did.
 */

/** [AboutFollowUpPrompt.prepare] over the song's Room rows. */
fun AboutFollowUpPrompt.prepare(
    entries: List<SongAboutEntry>,
    asked: Collection<String>,
    localeTag: String,
): AboutFollowUpPrompt.Prepared? = prepare(entries.map(SongAboutEntry::toFollowUpEntry), asked, localeTag)

internal fun SongAboutEntry.toFollowUpEntry(): AboutFollowUpEntry = AboutFollowUpEntry(
    kind = kind,
    entryKey = entryKey,
    titleDisplay = titleDisplay,
    artistDisplay = artistDisplay,
    titleText = titleText,
    promptText = promptText,
    answerText = answerText,
)
