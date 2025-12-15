package org.example.domain.repository

import org.example.domain.model.McpTool

/**
 * Репозиторий для работы с MCP (Model Context Protocol) сервером
 */
interface McpRepository {
    /**
     * Инициализирует соединение с MCP-сервером
     * 
     * @return Result с успешным результатом или ошибкой
     */
    suspend fun initialize(): Result<Unit>
    
    /**
     * Получает список всех доступных инструментов (tools) с MCP-сервера
     * 
     * @return Result со списком инструментов или ошибкой
     */
    suspend fun listTools(): Result<List<McpTool>>
    
    /**
     * Вызывает инструмент (tool) на MCP-сервере
     * 
     * @param toolName имя инструмента для вызова
     * @param arguments аргументы для передачи инструменту
     * @return Result с результатом выполнения инструмента или ошибкой
     */
    suspend fun callTool(toolName: String, arguments: Map<String, String>): Result<String>
    
    /**
     * Закрывает соединение с MCP-сервером
     */
    fun close()
}

