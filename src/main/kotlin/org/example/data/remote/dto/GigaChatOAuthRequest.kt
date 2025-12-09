package org.example.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTO для запроса получения Access token через OAuth
 */
@Serializable
data class GigaChatOAuthRequest(
    val scope: String = "GIGACHAT_API_PERS"
)
