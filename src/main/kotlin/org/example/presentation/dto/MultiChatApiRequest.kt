package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * DTO для входящего запроса с несколькими моделями
 */
@Serializable
data class MultiChatApiRequest(
    val message: String,
    /**
     * Список моделей для запроса
     * Каждая модель должна содержать vendor и model
     */
    val models: List<ModelConfig>,
    val maxTokens: Int? = null,
    val disableSearch: Boolean? = null,
    val systemPrompt: String? = null,
    val outputFormat: String? = null,
    val outputSchema: String? = null,
    val temperature: Double? = null
)

/**
 * Конфигурация модели для мульти-запроса
 */
@Serializable
data class ModelConfig(
    val vendor: String,
    val model: String? = null
)
