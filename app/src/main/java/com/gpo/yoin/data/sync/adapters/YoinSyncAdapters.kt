package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds

/** The v1 allowlist: one adapter per synced kind (spec §0). */
object YoinSyncAdapters {
    fun create(
        db: YoinDatabase,
        syncDb: SyncDatabase,
        seam: SeamStyleGateway,
        decodeCredentials: (Profile) -> ProfileCredentials?,
        profiles: suspend () -> List<Profile> = { db.syncDomainDao().allProfiles() },
        storefront: suspend (localProfileId: String) -> String? = { null },
        clock: () -> Long = System::currentTimeMillis,
    ): List<SyncAdapter> = listOf(
        SongNoteSyncAdapter(db),
        TrackRatingSyncAdapter(db),
        AlbumRatingSyncAdapter(db),
        AlbumReviewSyncAdapter(db),
        HomeLayoutSyncAdapter(db),
        SettingsSyncAdapter(
            db = db,
            seam = seam,
            tracked = { key ->
                syncDb.syncDao().getLocalState(SyncKinds.SETTING, SyncFormat.GLOBAL_SCOPE, key)?.localHash != null
            },
        ),
        LyricsTranslationSyncAdapter(db, clock),
        AccountDescriptorSyncAdapter(syncDb, profiles, decodeCredentials, storefront),
    )
}
