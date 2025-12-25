package org.example.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа от Local API
 */
@Serializable
data class LocalResponse(
    val choices: List<LocalChoice>,
    val model: String? = null,
    val usage: LocalUsage? = null
)

@Serializable
data class LocalChoice(
    val message: LocalMessage
)

@Serializable
data class LocalUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)

