package org.example.data.transport

import kotlinx.serialization.json.JsonObject

/**
 * Интерфейс транспорта для MCP (Model Context Protocol) клиента
 */
interface McpTransport {
    /**
     * Отправляет JSON-RPC запрос и получает ответ
     * 
     * @param requestBody JSON-RPC запрос в виде JsonObject
     * @return Result с JsonObject ответом или ошибкой
     */
    suspend fun sendRequest(requestBody: JsonObject): Result<JsonObject>
    
    /**
     * Закрывает соединение и освобождает ресурсы
     */
    fun close()
    
    /**
     * Проверяет, открыто ли соединение
     */
    fun isOpen(): Boolean
}



