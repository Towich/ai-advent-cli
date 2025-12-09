package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа клиенту
 */
@Serializable
data class ChatApiResponse(
    val content: String,
    val model: String? = null,
    val isComplete: Boolean,
    val round: Int,
    val maxRounds: Int,
    val executionTimeMs: Long? = null
)


