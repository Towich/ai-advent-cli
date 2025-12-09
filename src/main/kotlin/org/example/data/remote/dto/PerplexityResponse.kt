package org.example.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа от Perplexity API
 */
@Serializable
data class PerplexityResponse(
    val choices: List<PerplexityChoice>,
    val model: String? = null,
    val usage: PerplexityUsage? = null
)

@Serializable
data class PerplexityChoice(
    val message: PerplexityMessage
)

@Serializable
data class PerplexityUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)


