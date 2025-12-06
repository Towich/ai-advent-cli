package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа с ошибкой
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null
)


