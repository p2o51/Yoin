/*
 * TEST-ONLY REFERENCE: the Android implementation as it was before it moved to :shared
 * (git show 69bfd77a0:<path>), kept verbatim apart from the package and visibility so the parity
 * tests can prove the shared code returns the same values.
 */
package com.gpo.yoin.shared.reference

fun refScrapHash(text: String): Long {
    var h = 0x811C9DC5.toInt()
    for (c in text) {
        h = h xor c.code
        h *= 16777619
    }
    return h.toLong() and 0xFFFFFFFFL
}
