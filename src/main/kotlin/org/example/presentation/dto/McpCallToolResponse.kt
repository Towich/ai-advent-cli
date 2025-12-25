package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * Ответ на вызов инструмента MCP
 */
@Serializable
data class McpCallToolResponse(
    val toolName: String,
    val result: String,
    val success: Boolean = true
)


