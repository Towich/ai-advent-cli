package org.example.domain.model

/**
 * Модель инструмента (tool) из MCP-сервера
 * 
 * Примечание: inputSchema хранится как Map<String, JsonElement> для гибкости,
 * но не сериализуется напрямую (используется только для внутренней обработки)
 */
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val serverUrl: String? = null // URL MCP-сервера, откуда пришел этот инструмент
)

