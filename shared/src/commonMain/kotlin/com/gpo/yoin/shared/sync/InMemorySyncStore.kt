package com.gpo.yoin.shared.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [SyncStore] held in memory, serialisable to one JSON document ([snapshotJson] /
 * [restore]). iOS persists it after every operation ([isDirty] + [markClean]);
 * tests use it as Android tests use `SyncDatabase.inMemory`.
 *
 * Not thread-safe: like Android's engine the caller serialises every call
 * (the iOS coordinator runs on the main dispatcher under one Mutex).
 * Transactions snapshot the whole state and restore it on a throw.
 */
class InMemorySyncStore : SyncStore {
    private data class Rk(val kind: String, val scope: String, val key: String)

    private var records = LinkedHashMap<Rk, SyncRecordEntity>()
    private var states = LinkedHashMap<Rk, SyncLocalStateEntity>()
    private var bindingsByProfile = LinkedHashMap<String, SyncBindingEntity>()
    private var remote = LinkedHashMap<String, SyncRemoteFileEntity>()
    private var metaMap = LinkedHashMap<String, String>()
    private var depth = 0

    /** true when something changed since the last [markClean] / [restore]. */
    var isDirty: Boolean = false
        private set

    fun markClean() {
        isDirty = false
    }

    private fun touch() {
        isDirty = true
    }

    override suspend fun <T> transaction(block: suspend () -> T): T {
        if (depth > 0) return block()
        val saved = Snapshot(records.values.toList(), states.values.toList(), bindingsByProfile.values.toList(),
            remote.values.toList(), metaMap.map { SyncMetaEntity(it.key, it.value) })
        val wasDirty = isDirty
        depth++
        try {
            return block()
        } catch (e: Throwable) {
            load(saved)
            isDirty = wasDirty
            throw e
        } finally {
            depth--
        }
    }

    // ---- records
    override suspend fun getRecord(kind: String, scope: String, key: String) = records[Rk(kind, scope, key)]

    override suspend fun recordsFor(kind: String, scope: String) =
        records.values.filter { it.kind == kind && it.scope == scope }

    override suspend fun recordsInScope(scope: String) = records.values.filter { it.scope == scope }

    override suspend fun allRecords() = records.values.toList()

    override suspend fun recordsOfKind(kind: String) = records.values.filter { it.kind == kind }

    override suspend fun liveCount(kind: String, scope: String) =
        records.values.count { it.kind == kind && it.scope == scope && !it.deleted }

    override suspend fun upsertRecord(record: SyncRecordEntity) {
        records[Rk(record.kind, record.scope, record.key)] = record
        touch()
    }

    override suspend fun upsertRecords(records: List<SyncRecordEntity>) {
        for (record in records) upsertRecord(record)
    }

    override suspend fun clearRecords() {
        records.clear()
        touch()
    }

    // ---- local state
    override suspend fun getLocalState(kind: String, scope: String, key: String) = states[Rk(kind, scope, key)]

    override suspend fun localStatesFor(kind: String, scope: String) =
        states.values.filter { it.kind == kind && it.scope == scope }

    override suspend fun localStatesInScope(scope: String) = states.values.filter { it.scope == scope }

    override suspend fun pendingLocalStates() = states.values.filter { it.pending }

    override suspend fun pendingCount() = states.values.count { it.pending }

    override suspend fun upsertLocalState(state: SyncLocalStateEntity) {
        states[Rk(state.kind, state.scope, state.key)] = state
        touch()
    }

    override suspend fun upsertLocalStates(states: List<SyncLocalStateEntity>) {
        for (state in states) upsertLocalState(state)
    }

    override suspend fun deleteLocalState(kind: String, scope: String, key: String) {
        if (states.remove(Rk(kind, scope, key)) != null) touch()
    }

    override suspend fun deleteLocalStatesInScope(scope: String) {
        if (states.keys.removeAll { it.scope == scope }) touch()
    }

    override suspend fun deleteLocalStatesForProfile(localProfileId: String) {
        if (states.values.removeAll { it.boundProfileId == localProfileId }) touch()
    }

    override suspend fun clearLocalStates() {
        states.clear()
        touch()
    }

    // ---- bindings
    override suspend fun bindings() = bindingsByProfile.values.toList()

    override suspend fun bindingFor(localProfileId: String) = bindingsByProfile[localProfileId]

    override suspend fun bindingForScope(syncProfileId: String) =
        bindingsByProfile.values.firstOrNull { it.syncProfileId == syncProfileId }

    override suspend fun upsertBinding(binding: SyncBindingEntity) {
        // Room's unique index on syncProfileId: a second profile can never hold the same scope.
        val holder = bindingForScope(binding.syncProfileId)
        require(holder == null || holder.localProfileId == binding.localProfileId) {
            "UNIQUE constraint failed: sync_bindings.syncProfileId"
        }
        bindingsByProfile[binding.localProfileId] = binding
        touch()
    }

    override suspend fun deleteBinding(localProfileId: String) {
        if (bindingsByProfile.remove(localProfileId) != null) touch()
    }

    // ---- remote files
    override suspend fun remoteFiles() = remote.values.toList()

    override suspend fun remoteFile(fileId: String) = remote[fileId]

    override suspend fun upsertRemoteFile(file: SyncRemoteFileEntity) {
        remote[file.fileId] = file
        touch()
    }

    override suspend fun deleteRemoteFile(fileId: String) {
        if (remote.remove(fileId) != null) touch()
    }

    override suspend fun clearRemoteFiles() {
        remote.clear()
        touch()
    }

    // ---- meta
    override suspend fun meta(key: String) = metaMap[key]

    override suspend fun metaWithPrefix(prefix: String) =
        metaMap.filterKeys { it.startsWith(prefix) }.map { SyncMetaEntity(it.key, it.value) }

    override suspend fun putMeta(entry: SyncMetaEntity) {
        metaMap[entry.key] = entry.value
        touch()
    }

    override suspend fun deleteMeta(key: String) {
        if (metaMap.remove(key) != null) touch()
    }

    // ---- persistence

    @Serializable
    private data class Snapshot(
        @SerialName("records") val records: List<SyncRecordEntity>,
        @SerialName("localStates") val localStates: List<SyncLocalStateEntity>,
        @SerialName("bindings") val bindings: List<SyncBindingEntity>,
        @SerialName("remoteFiles") val remoteFiles: List<SyncRemoteFileEntity>,
        @SerialName("meta") val meta: List<SyncMetaEntity>,
        @SerialName("version") val version: Int = 1,
    )

    private fun load(snapshot: Snapshot) {
        records = LinkedHashMap(snapshot.records.associateBy { Rk(it.kind, it.scope, it.key) })
        states = LinkedHashMap(snapshot.localStates.associateBy { Rk(it.kind, it.scope, it.key) })
        bindingsByProfile = LinkedHashMap(snapshot.bindings.associateBy { it.localProfileId })
        remote = LinkedHashMap(snapshot.remoteFiles.associateBy { it.fileId })
        metaMap = LinkedHashMap(snapshot.meta.associate { it.key to it.value })
    }

    /** The whole store as JSON. */
    fun snapshotJson(): String = json.encodeToString(
        Snapshot.serializer(),
        Snapshot(records.values.toList(), states.values.toList(), bindingsByProfile.values.toList(),
            remote.values.toList(), metaMap.map { SyncMetaEntity(it.key, it.value) }),
    )

    /** Replaces the content with a [snapshotJson] document; false (store unchanged) when it can't be read. */
    fun restore(text: String): Boolean {
        val snapshot = runCatching { json.decodeFromString(Snapshot.serializer(), text) }.getOrNull() ?: return false
        load(snapshot)
        isDirty = false
        return true
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
