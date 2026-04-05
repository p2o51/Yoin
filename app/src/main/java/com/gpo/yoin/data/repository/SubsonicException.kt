package com.gpo.yoin.data.repository

class SubsonicException(
    val code: Int,
    message: String?,
) : Exception(message ?: "Subsonic error (code $code)")
