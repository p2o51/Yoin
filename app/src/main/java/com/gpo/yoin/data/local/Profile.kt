package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One account / backend the user has configured. [credentialsJson] is an
 * encrypted blob whose shape is provider-specific (see
 * `com.gpo.yoin.data.profile.ProfileCredentials`).
 */
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey val id: String,
    val provider: String,
    val displayName: String,
    val credentialsJson: String,
    val createdAt: Long = System.currentTimeMillis(),
)
