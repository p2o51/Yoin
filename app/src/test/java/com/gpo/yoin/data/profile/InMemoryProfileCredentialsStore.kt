package com.gpo.yoin.data.profile

/**
 * Test fake for [ProfileCredentialsStore]. Backed by a plain
 * `MutableMap` keyed by profile id — no envelope, no cipher, no IO. Use
 * this for ProfileManager tests that care about wiring (which file gets
 * written when, which gets read on decode) rather than the encryption
 * path itself; the codec / cipher have their own tests.
 */
internal class InMemoryProfileCredentialsStore : ProfileCredentialsStore {

    private val store = mutableMapOf<String, ProfileCredentials>()

    override fun write(profileId: String, credentials: ProfileCredentials) {
        store[profileId] = credentials
    }

    override fun read(profileId: String): ProfileCredentials? = store[profileId]

    override fun delete(profileId: String) {
        store.remove(profileId)
    }

    val size: Int get() = store.size

    fun contains(profileId: String): Boolean = profileId in store

    fun snapshot(profileId: String): ProfileCredentials? = store[profileId]
}
