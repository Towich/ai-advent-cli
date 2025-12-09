package org.example.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа OAuth с Access token
 */
@Serializable
data class GigaChatOAuthResponse(
    val access_token: String,
    val expires_at: Long? = null
)
