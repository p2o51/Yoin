package com.gpo.yoin.shared.data.model

/**
 * Credits found at the start and end of a song's lyrics, in file order (the Android app's model).
 * [roles] are "role : names" lines (plus a few free-form English lines such as "Recorded at …", whose
 * [LyricCredit.role] is empty); [notices] are copyright and licensing lines, shown small at the bottom of
 * About.
 */
data class LyricCredits(
    val roles: List<LyricCredit>,
    val notices: List<String> = emptyList(),
) {
    /** Names for one of the four headline roles, merged across lines; null when absent. */
    fun namesFor(role: LyricCreditRole): String? =
        roles.filter { it.headline == role }
            .map(LyricCredit::names)
            .filter(String::isNotBlank)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" / ")

    /** Every credit that isn't one of the four headline roles. */
    val others: List<LyricCredit> get() = roles.filter { it.headline == null }

    fun isEmpty(): Boolean = roles.isEmpty() && notices.isEmpty()
}

data class LyricCredit(
    /** The role as the source wrote it ("混音", "Bass Guitar"); empty for a free-form line. */
    val role: String,
    val names: String,
    val headline: LyricCreditRole? = null,
)

/** The roles About shows up front, in this order. */
enum class LyricCreditRole { Lyricist, Composer, Arranger, Producer }
