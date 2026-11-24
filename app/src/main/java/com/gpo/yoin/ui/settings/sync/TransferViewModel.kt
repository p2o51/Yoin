package com.gpo.yoin.ui.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.sync.CloudScopeCounts
import com.gpo.yoin.data.sync.CloudSyncController
import com.gpo.yoin.data.transfer.ReviewRow
import com.gpo.yoin.data.transfer.ReviewStatus
import com.gpo.yoin.data.transfer.memories.MemorySource
import com.gpo.yoin.data.transfer.memories.MemoryTransfer
import com.gpo.yoin.data.transfer.memories.TransferPlan
import com.gpo.yoin.shared.portability.BackupFormat
import com.gpo.yoin.shared.portability.memories.MatchStatus
import com.gpo.yoin.shared.portability.memories.TransferAccount
import com.gpo.yoin.shared.portability.memories.TransferLedger
import com.gpo.yoin.shared.portability.memories.TransferResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a transfer starts: any of these may be preset by the entry (account card, account page, re-sign-in). */
data class TransferArgs(
    val sourceProfileId: String? = null,
    val sourceCloudScope: String? = null,
    val targetProfileId: String? = null,
    /**
     * The cloud source's service, when the entry knows it (the previous account of a re-sign-in): used if the
     * replica doesn't describe [sourceCloudScope] yet, so the target list still tells a same-service copy.
     */
    val sourceProvider: String? = null,
)

enum class TransferStep { Loading, Source, Target, Summary, Matching, Review, Copying, Done, Failed }

/** How the pair copies: same ids ([Direct]), mostly the same (Apple Music library songs, [Partial]) or [Match]. */
enum class TransferKind { Direct, Partial, Match }

enum class TransferFailure { ReadFailed, TargetUnreachable, RunFailed }

/** One account the flow can copy from or into. [counts] when the cloud replica knows them. */
data class TransferAccountUi(
    val key: String,
    val source: MemorySource,
    val account: TransferAccount,
    val counts: CloudScopeCounts? = null,
    val cloudOnly: Boolean = false,
    val fromDevice: String? = null,
    val hint: String? = null,
)

data class TransferUiState(
    val step: TransferStep = TransferStep.Loading,
    /** The step Back closes the flow from (the first one the entry didn't preset). */
    val firstStep: TransferStep = TransferStep.Source,
    val accounts: List<TransferAccountUi> = emptyList(),
    val sourceKey: String? = null,
    val targetKey: String? = null,
    /** The plan without matching: what would be copied, what was copied before, what needs matching. */
    val draft: TransferPlan? = null,
    val draftLoading: Boolean = false,
    /** The plan after matching, reviewed through [rows] (position = index into its tracks). */
    val plan: TransferPlan? = null,
    val rows: List<ReviewRow> = emptyList(),
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val result: TransferResult? = null,
    val removeCard: Boolean = false,
    val cleaned: Boolean = false,
    /** Removing the source's cloud data right now. */
    val cleaning: Boolean = false,
    /** Done was confirmed: the card removal (if any) is running, the flow closes after it. */
    val finishing: Boolean = false,
    val failure: TransferFailure? = null,
    /** Cloud sync is on here: the done page offers removing the source's cloud data. */
    val cloudSyncOn: Boolean = false,
) {
    val source: TransferAccountUi? get() = accounts.firstOrNull { it.key == sourceKey }
    val target: TransferAccountUi? get() = accounts.firstOrNull { it.key == targetKey }

    /** Accounts Memories can come from. */
    val sources: List<TransferAccountUi> get() = accounts

    /** Profiles on this device other than the source. */
    val targets: List<TransferAccountUi>
        get() = accounts.filter { it.account.localProfileId != null && it.key != sourceKey }

    /** Whether the chosen pair needs a matching pass (known once the draft is read). */
    val needsMatching: Boolean get() = draft?.let { it.tracks.isNotEmpty() || it.albums.isNotEmpty() } ?: true

    val totalItems: Int get() = draft?.let { MemoryTransfer.countItems(it.items) } ?: 0
    val remainingItems: Int get() = draft?.let { (totalItems - it.previouslyCopied).coerceAtLeast(0) } ?: 0

    /**
     * Songs whose ids carry over as they are and that still have something to copy (a rerun leaves out what an
     * earlier run copied).
     */
    val directSongs: Int
        get() = draft?.let { plan ->
            val pending = MemoryTransfer.pendingItems(plan.items) { key ->
                plan.ledger[key]?.startsWith(TransferLedger.COPIED) != true
            }
            val matching = plan.tracks.mapTo(HashSet()) { it.source }
            pending.tracks.count { it !in matching }
        } ?: 0

    /** Songs whose data the run will copy: the direct ones plus the checked rows. */
    val songsToCopy: Int get() = directSongs + rows.count { it.checked }

    /** The review's copy button has something to copy: a song's data, or an album matched by its songs or by search. */
    val canCopyReviewed: Boolean
        get() = songsToCopy > 0 || plan?.albums?.any { it.status == MatchStatus.Matched || it.status == MatchStatus.Picked } == true

    /** Steps in this flow, for the "Step n of m" line: the review only when something needs matching. */
    val stepCount: Int get() = if (draft != null && !needsMatching) 4 else 5

    fun stepIndex(): Int = when (step) {
        TransferStep.Loading, TransferStep.Source -> 0
        TransferStep.Target -> 1
        TransferStep.Summary -> 2
        TransferStep.Matching, TransferStep.Review -> 3
        TransferStep.Copying, TransferStep.Done, TransferStep.Failed -> stepCount - 1
    }
}

/** The pair's kind from the providers alone, for the target list's badge. */
fun transferKindOf(source: TransferAccount, target: TransferAccount): TransferKind = when {
    source.provider != target.provider -> TransferKind.Match
    source.provider == MediaId.PROVIDER_SPOTIFY -> TransferKind.Direct
    source.provider == MediaId.PROVIDER_APPLE_MUSIC -> TransferKind.Partial
    source.provider == MediaId.PROVIDER_SUBSONIC &&
        source.serverKey != null && source.serverKey.equals(target.serverKey, ignoreCase = true) -> TransferKind.Direct
    else -> TransferKind.Match
}

/** The review's rows for [plan]'s songs: matched checked, unsure preselected but unchecked, not found empty. */
internal fun reviewRowsOf(plan: TransferPlan): List<ReviewRow> = plan.tracks.mapIndexed { index, match ->
    when (match.status) {
        MatchStatus.Matched, MatchStatus.Picked, MatchStatus.Direct -> ReviewRow(
            position = index,
            status = if (match.status == MatchStatus.Picked) ReviewStatus.Picked else ReviewStatus.Matched,
            candidates = listOfNotNull(match.chosen).ifEmpty { match.candidates.take(1) },
            chosen = 0,
            checked = match.chosen != null || match.candidates.isNotEmpty(),
        )
        MatchStatus.Uncertain -> ReviewRow(index, ReviewStatus.Uncertain, match.candidates, chosen = 0, checked = false)
        MatchStatus.Unmatched -> ReviewRow(index, ReviewStatus.NotFound, emptyList(), chosen = 0, checked = false)
    }
}

/** [plan] with the review's decisions: checked rows take their chosen candidate, unchecked ones copy nothing. */
internal fun applyReview(plan: TransferPlan, rows: List<ReviewRow>): TransferPlan {
    var out = plan
    rows.forEach { row ->
        val match = plan.tracks.getOrNull(row.position) ?: return@forEach
        val choice = row.choice
        val unchanged = row.checked && choice != null && choice.id == match.chosen?.id &&
            (match.status == MatchStatus.Matched || match.status == MatchStatus.Picked)
        out = when {
            unchanged -> out
            row.checked && choice != null -> out.pickTrack(match.source, choice)
            match.chosen != null -> out.pickTrack(match.source, null)
            else -> out
        }
    }
    return out
}

sealed interface TransferEvent {
    /** The flow is over: close, after the snackbar text (if any) is shown by the caller. */
    data class Finished(val message: TransferFinishMessage?) : TransferEvent

    data class Message(val text: TransferMessage) : TransferEvent
}

sealed interface TransferFinishMessage {
    data class Copied(val sourceName: String) : TransferFinishMessage

    data class CardRemoved(val sourceName: String) : TransferFinishMessage
}

sealed interface TransferMessage {
    data class Cleaned(val sourceName: String) : TransferMessage

    data class CleanFailed(val sourceName: String) : TransferMessage

    data object Busy : TransferMessage
}

/**
 * 转移 Memories, one step at a time (prototype s5–s7): source → target → summary → matching → review → copy →
 * done. Copy, never move: nothing is written before the copy step, and the copy finishes even if the page closes.
 */
class TransferViewModel(
    private val ops: MemoriesOps,
    private val controller: CloudSyncController,
    private val args: TransferArgs = TransferArgs(),
) : ViewModel() {
    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    private val _events = Channel<TransferEvent>(Channel.BUFFERED)
    val events: Flow<TransferEvent> = _events.receiveAsFlow()

    private var job: Job? = null

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val sources = try {
                ops.transferSources()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            // Counts come from the cloud replica: only meaningful while Cloud sync is on here.
            val summary = if (controller.state.value.enabled) runCatching { controller.cloudDataSummary() }.getOrNull() else null
            val cloudRows = controller.state.value.cloudOnlyAccounts.associateBy { it.syncProfileId }
            val accounts = sources.map { (source, account) ->
                val cloud = (source as? MemorySource.CloudAccount)?.let { cloudRows[it.syncProfileId] }
                TransferAccountUi(
                    key = keyOf(source),
                    source = source,
                    account = account,
                    counts = summary?.scopes?.get(account.scope) ?: summary?.let { CloudScopeCounts() }
                        ?.takeIf { BackupFormat.isSyncScope(account.scope) },
                    cloudOnly = source is MemorySource.CloudAccount,
                    fromDevice = cloud?.fromDeviceName,
                    hint = cloud?.hint,
                )
            }.toMutableList()
            // A preset cloud source the list doesn't name (its descriptor not in the replica yet): read it anyway.
            args.sourceCloudScope?.let { scope ->
                if (accounts.none { it.key == cloudKey(scope) }) {
                    val source = MemorySource.CloudAccount(scope)
                    accounts += TransferAccountUi(
                        key = cloudKey(scope),
                        source = source,
                        account = TransferAccount(
                            scope,
                            args.sourceProvider ?: cloudRows[scope]?.provider ?: UNKNOWN_PROVIDER,
                            cloudRows[scope]?.displayName ?: scope,
                            null,
                            null,
                        ),
                        counts = summary?.scopes?.get(scope),
                        cloudOnly = true,
                    )
                }
            }
            val sourceKey = args.sourceProfileId?.let(::localKey) ?: args.sourceCloudScope?.let(::cloudKey)
            val validSource = sourceKey?.takeIf { key -> accounts.any { it.key == key } }
            val targetKey = args.targetProfileId?.let(::localKey)
                ?.takeIf { key -> key != validSource && accounts.any { it.key == key } }
            val first = when {
                validSource == null -> TransferStep.Source
                targetKey == null -> TransferStep.Target
                else -> TransferStep.Summary
            }
            _state.value = TransferUiState(
                step = first,
                firstStep = first,
                accounts = accounts,
                sourceKey = validSource,
                targetKey = targetKey,
                cloudSyncOn = controller.state.value.enabled,
            )
            if (first == TransferStep.Summary) loadDraft()
        }
    }

    fun pickSource(key: String) {
        _state.update { state ->
            if (state.step != TransferStep.Source) return@update state
            state.copy(sourceKey = key, targetKey = state.targetKey?.takeIf { it != key }, draft = null)
        }
    }

    fun pickTarget(key: String) {
        _state.update { state ->
            if (state.step != TransferStep.Target || state.targets.none { it.key == key }) return@update state
            state.copy(targetKey = key, draft = null)
        }
    }

    fun next() {
        val state = _state.value
        when (state.step) {
            TransferStep.Source -> if (state.sourceKey != null) _state.value = state.copy(step = TransferStep.Target)
            TransferStep.Target -> if (state.targetKey != null) {
                _state.value = state.copy(step = TransferStep.Summary)
                loadDraft()
            }
            else -> Unit
        }
    }

    /** From the target step: choose the source again. */
    fun changeSource() {
        _state.update { it.copy(step = TransferStep.Source, firstStep = TransferStep.Source) }
    }

    /** Back inside the flow; false = close the flow. */
    fun back(): Boolean {
        val state = _state.value
        return when (state.step) {
            TransferStep.Loading -> false
            TransferStep.Matching, TransferStep.Copying -> {
                _events.trySend(TransferEvent.Message(TransferMessage.Busy))
                true
            }
            TransferStep.Done -> {
                finish()
                true
            }
            TransferStep.Failed -> false
            else -> {
                if (state.step == state.firstStep) return false
                val previous = when (state.step) {
                    TransferStep.Target -> TransferStep.Source
                    TransferStep.Summary -> TransferStep.Target
                    TransferStep.Review -> TransferStep.Summary
                    else -> state.firstStep
                }
                job?.cancel()
                _state.value = state.copy(step = previous, draftLoading = false)
                true
            }
        }
    }

    private fun loadDraft() {
        val state = _state.value
        val source = state.source ?: return
        val targetId = state.target?.account?.localProfileId ?: return
        job?.cancel()
        _state.value = state.copy(draft = null, draftLoading = true)
        job = viewModelScope.launch {
            try {
                val draft = ops.planTransfer(source.source, targetId, catalog = null)
                _state.update { it.copy(draft = draft, draftLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(draftLoading = false, step = TransferStep.Failed, failure = TransferFailure.ReadFailed) }
            }
        }
    }

    /** The summary's button: match (when needed) or copy straight away. */
    fun start() {
        val state = _state.value
        val draft = state.draft ?: return
        if (state.step != TransferStep.Summary || state.remainingItems == 0) return
        if (!state.needsMatching) {
            copy(draft)
            return
        }
        val source = state.source ?: return
        val targetId = state.target?.account?.localProfileId ?: return
        val total = draft.tracks.size + draft.albums.size
        _state.value = state.copy(step = TransferStep.Matching, progressDone = 0, progressTotal = total)
        job = viewModelScope.launch {
            try {
                val plan = ops.withCatalog(source.source, targetId) { catalog ->
                    ops.planTransfer(source.source, targetId, catalog) { done, all ->
                        _state.update { if (it.step == TransferStep.Matching) it.copy(progressDone = done, progressTotal = all) else it }
                    }
                }
                _state.update { current ->
                    if (current.step != TransferStep.Matching) return@update current
                    if (plan == null) {
                        current.copy(step = TransferStep.Failed, failure = TransferFailure.TargetUnreachable)
                    } else {
                        current.copy(step = TransferStep.Review, plan = plan, rows = reviewRowsOf(plan))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(step = TransferStep.Failed, failure = TransferFailure.TargetUnreachable) }
            }
        }
    }

    /** Matching or the review dropped: nothing was written. */
    fun cancel() {
        val state = _state.value
        if (state.step == TransferStep.Copying || state.step == TransferStep.Done) return
        job?.cancel()
        _events.trySend(TransferEvent.Finished(null))
    }

    fun setChecked(position: Int, checked: Boolean) = updateRows { rows ->
        rows.map { if (it.position == position && it.checkable) it.copy(checked = checked) else it }
    }

    /** Picking a candidate is consent: the row is checked. */
    fun choose(position: Int, index: Int) = updateRows { rows ->
        rows.map { if (it.position == position && index in it.candidates.indices) it.copy(chosen = index, checked = true) else it }
    }

    private fun updateRows(change: (List<ReviewRow>) -> List<ReviewRow>) {
        _state.update { if (it.step == TransferStep.Review) it.copy(rows = change(it.rows)) else it }
    }

    /** The review's button. */
    fun copyReviewed() {
        val state = _state.value
        val plan = state.plan ?: return
        if (state.step != TransferStep.Review) return
        copy(applyReview(plan, state.rows))
    }

    private fun copy(plan: TransferPlan) {
        _state.update { it.copy(step = TransferStep.Copying) }
        job = viewModelScope.launch {
            // Once copying starts it finishes, page or not.
            val result = withContext(NonCancellable) {
                try {
                    ops.runTransfer(plan)
                } catch (_: Exception) {
                    null
                }
            }
            _state.update {
                if (result == null) {
                    it.copy(step = TransferStep.Failed, failure = TransferFailure.RunFailed)
                } else {
                    it.copy(step = TransferStep.Done, result = result)
                }
            }
        }
    }

    fun setRemoveCard(remove: Boolean) {
        _state.update { if (it.source?.account?.localProfileId != null) it.copy(removeCard = remove) else it }
    }

    /** The done page's "remove the original's cloud data", after its confirmation. */
    fun cleanSource() {
        val state = _state.value
        val source = state.source ?: return
        val scope = source.account.scope
        if (state.cleaned || state.cleaning || !BackupFormat.isSyncScope(scope)) return
        _state.update { it.copy(cleaning = true) }
        viewModelScope.launch {
            val ok = runCatching { controller.removeAccountFromCloud(scope) }.getOrElse { Result.failure(it) }.isSuccess
            _state.update { it.copy(cleaning = false, cleaned = it.cleaned || ok) }
            _events.trySend(
                TransferEvent.Message(
                    if (ok) TransferMessage.Cleaned(source.account.displayName) else TransferMessage.CleanFailed(source.account.displayName),
                ),
            )
        }
    }

    /** Done: remove the source's card if asked, then close. Runs once, however often Done or Back is pressed. */
    fun finish() {
        val state = _state.value
        if (state.finishing) return
        _state.value = state.copy(finishing = true)
        val source = state.source
        val profileId = source?.account?.localProfileId
        viewModelScope.launch {
            val removed = if (state.removeCard && profileId != null) {
                withContext(NonCancellable) { runCatching { ops.removeProfile(profileId) }.isSuccess }
            } else {
                false
            }
            val name = source?.account?.displayName.orEmpty()
            _events.trySend(
                TransferEvent.Finished(
                    when {
                        state.step != TransferStep.Done -> null
                        removed -> TransferFinishMessage.CardRemoved(name)
                        else -> TransferFinishMessage.Copied(name)
                    },
                ),
            )
        }
    }

    companion object {
        private const val UNKNOWN_PROVIDER = "unknown"

        fun localKey(profileId: String) = "local:$profileId"

        fun cloudKey(scope: String) = "cloud:$scope"

        fun keyOf(source: MemorySource): String = when (source) {
            is MemorySource.LocalProfile -> localKey(source.profileId)
            is MemorySource.CloudAccount -> cloudKey(source.syncProfileId)
            is MemorySource.BackupAccount -> "backup:${source.scope}"
        }
    }

    class Factory(
        private val ops: MemoriesOps,
        private val controller: CloudSyncController,
        private val args: TransferArgs,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TransferViewModel(ops, controller, args) as T
    }
}
