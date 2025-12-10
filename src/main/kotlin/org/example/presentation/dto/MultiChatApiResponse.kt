package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа с объединенными результатами от нескольких моделей
 */
@Serializable
data class MultiChatApiResponse(
    /**
     * Объединенный ответ от всех моделей
     */
    val combinedContent: String,
    /**
     * Детальные ответы от каждой модели
     */
    val responses: List<ModelResponse>,
    /**
     * Общее время выполнения всех запросов в миллисекундах
     */
    val totalExecutionTimeMs: Long,
    /**
     * Общее использование токенов (сумма всех моделей)
     */
    val totalUsage: Usage? = null
)

/**
 * Ответ от одной модели
 */
@Serializable
data class ModelResponse(
    val vendor: String,
    val model: String,
    val content: String,
    val executionTimeMs: Long,
    val usage: Usage? = null,
    val success: Boolean,
    val error: String? = null
)
