package org.example.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа от Hugging Face API
 */
@Serializable
data class HuggingFaceResponse(
    val choices: List<HuggingFaceChoice>,
    val model: String? = null,
    val usage: HuggingFaceUsage? = null
)

@Serializable
data class HuggingFaceChoice(
    val message: HuggingFaceMessage,
    val finish_reason: String? = null,
    val index: Int? = null
)

@Serializable
data class HuggingFaceUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)
