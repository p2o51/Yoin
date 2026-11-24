package com.gpo.yoin.data.openin

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.shared.openin.YoinMusicLink
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.shared.openin.YoinMusicLinkParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The clipboard prompt's card (docs/open-in-yoin.md). [link] is the link as offered, then as followed once a
 * short link's target is known ([resolved]). [metadata] arrives a beat later, or never: the card shows its
 * plain line until then. A [OpenInTarget.Hit] is drawn rich (cover, title, artist, Listen / Open); a
 * [OpenInTarget.Found] link is text only, with Search.
 */
data class OpenInPrompt(
    val serial: Long,
    val link: YoinMusicLink,
    val target: OpenInTarget,
    val metadata: MusicLinkMetadata? = null,
    val resolved: Boolean = !link.needsRedirectResolution,
) {
    /** Listen for a song, Open for anything else (and for a short link whose target isn't known yet). */
    val listens: Boolean get() = link.kind == YoinMusicLinkKind.Track
}

/** What the shell does next, from a share or a tapped prompt. */
sealed interface OpenInRequest {
    /** Open (or play) [link] on [target]'s account, switching to it first if another is in use. */
    data class OpenHit(val link: YoinMusicLink, val target: OpenInTarget.Hit) : OpenInRequest

    /** Search the account in use for what [link] names and let the user choose ([metadata]: already read). */
    data class ShowResults(val link: YoinMusicLink, val metadata: MusicLinkMetadata?) : OpenInRequest

    /** Shared text that holds no music link: Library search with it. */
    data class SearchLibrary(val query: String) : OpenInRequest
}

/**
 * "Open in Yoin" (share target) and the clipboard prompt, behind one router (docs/open-in-yoin.md): a link
 * goes to an account on its own service when the user has one ([OpenInTarget.Hit]), else it is searched on
 * the account in use ([OpenInTarget.Found]).
 *
 * Holds no clipboard text: [offerClipboard] gets the parsed link only, and nothing here is written anywhere.
 */
class OpenInCoordinator(
    private val scope: CoroutineScope,
    private val lookup: MusicLinkLookup,
    private val profiles: suspend () -> List<Profile>,
    private val activeProfileId: () -> String?,
) {
    private val _prompt = MutableStateFlow<OpenInPrompt?>(null)
    val prompt: StateFlow<OpenInPrompt?> = _prompt.asStateFlow()

    // Buffered: a share that arrives before the shell is up (a cold start, the landing) waits for it.
    private val requestChannel = Channel<OpenInRequest>(Channel.BUFFERED)
    val requests: Flow<OpenInRequest> = requestChannel.receiveAsFlow()

    private var serial = 0L
    private var promptJob: Job? = null

    /**
     * Show the prompt for [link] (read off the clipboard). False when there is no account to open or search
     * it in (before the first one is added): nothing is shown, and the caller doesn't count it as offered.
     */
    suspend fun offerClipboard(link: YoinMusicLink): Boolean {
        val accounts = profiles()
        if (accounts.isEmpty()) return false
        val target = OpenInRouting.route(link, accounts, activeProfileId())
        val prompt = OpenInPrompt(serial = ++serial, link = link, target = target)
        _prompt.value = prompt
        promptJob?.cancel()
        promptJob = scope.launch { fill(prompt) }
        return true
    }

    /** The card's way to its link's target and title: the short link followed, then its metadata read. */
    private suspend fun fill(prompt: OpenInPrompt) {
        val link = lookup.resolve(prompt.link)
        updatePrompt(prompt.serial) { it.copy(link = link ?: it.link, resolved = true) }
        val metadata = link?.let { lookup.metadata(it) } ?: return
        updatePrompt(prompt.serial) { it.copy(metadata = metadata) }
    }

    private fun updatePrompt(serial: Long, change: (OpenInPrompt) -> OpenInPrompt) {
        _prompt.update { current -> if (current?.serial == serial) change(current) else current }
    }

    /** The prompt's button: open the hit, or search for the found link. The card goes either way. */
    fun accept() {
        val prompt = _prompt.value ?: return
        _prompt.value = null
        val pending = promptJob
        scope.launch {
            val link = if (prompt.resolved) prompt.link else lookup.resolve(prompt.link)
            pending?.cancel()
            link ?: return@launch
            requestChannel.send(
                when (val target = prompt.target) {
                    is OpenInTarget.Hit -> OpenInRequest.OpenHit(link, target)
                    OpenInTarget.Found -> OpenInRequest.ShowResults(link, prompt.metadata)
                },
            )
        }
    }

    fun dismiss() {
        promptJob?.cancel()
        _prompt.value = null
    }

    /**
     * Text shared to "Open in Yoin". Its first music link opens as a tapped prompt would, without a card: the
     * user already said what they want. No link: Library search with the text.
     */
    fun offerShared(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val found = YoinMusicLinkParser.findFirst(trimmed)
            val link = found?.let { lookup.resolve(it) }
            val request = when {
                link == null -> searchRequest(trimmed, found)
                else -> when (val target = OpenInRouting.route(link, profiles(), activeProfileId())) {
                    is OpenInTarget.Hit -> OpenInRequest.OpenHit(link, target)
                    OpenInTarget.Found -> OpenInRequest.ShowResults(link, metadata = null)
                }
            }
            request?.let { requestChannel.send(it) }
        }
    }

    /** Library search with [text], minus a link that led nowhere; nothing when only that link was shared. */
    private fun searchRequest(text: String, deadLink: YoinMusicLink?): OpenInRequest? {
        val query = (deadLink?.let { text.replace(it.url, " ") } ?: text)
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_QUERY_LENGTH)
        return query.takeIf(String::isNotEmpty)?.let(OpenInRequest::SearchLibrary)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 200
        val WHITESPACE = Regex("\\s+")
    }
}
