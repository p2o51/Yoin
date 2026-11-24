package com.gpo.yoin.data.openin

import java.security.MessageDigest

/**
 * What the clipboard's description says, read without touching its content: reading the content is what
 * shows Android's "Yoin pasted from your clipboard" notice (docs/open-in-yoin.md).
 *
 * [timestampMs]: when the clip was set (`ClipDescription.getTimestamp`). [urlConfidence]: the system's
 * confidence that the clip holds a URL (API 31+ `getConfidenceScore(TextClassifier.TYPE_URL)`), null when it
 * hasn't classified the clip (yet) or can't. [sensitive]: the copying app marked it sensitive (a password).
 */
data class ClipDescriptionFacts(
    val mimeTypes: List<String>,
    val timestampMs: Long?,
    val urlConfidence: Float?,
    val sensitive: Boolean,
)

/** The clipboard prompt's rules, pure so they're tested on their own (docs/open-in-yoin.md). */
object ClipboardLinkGate {
    /** Below this, a classified clip is not taken for one holding a link. */
    const val MIN_URL_CONFIDENCE = 0.5f

    private val TEXT_MIME_TYPES = setOf("text/plain", "text/html", "text/uri-list")

    /**
     * Whether to read the clip's content at all. Never with the setting off, never a sensitive clip, never
     * one already looked at ([lastSeenTimestampMs], the same clip on the next return), only text, and not
     * when the system classified it and found no URL in it.
     */
    fun shouldRead(facts: ClipDescriptionFacts?, enabled: Boolean, lastSeenTimestampMs: Long?): Boolean {
        if (!enabled || facts == null || facts.sensitive) return false
        if (facts.timestampMs != null && facts.timestampMs == lastSeenTimestampMs) return false
        if (facts.mimeTypes.none { it.lowercase() in TEXT_MIME_TYPES }) return false
        val confidence = facts.urlConfidence
        return confidence == null || confidence >= MIN_URL_CONFIDENCE
    }

    /**
     * Whether a link read off the clipboard is offered: not when it is the one last offered
     * ([lastHandledHash]). Only [hash]es are kept, never the clip.
     */
    fun shouldOffer(linkHash: String, lastHandledHash: String?): Boolean = linkHash != lastHandledHash

    /** SHA-256 of [value] in hex: what is remembered of a handled link. */
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
