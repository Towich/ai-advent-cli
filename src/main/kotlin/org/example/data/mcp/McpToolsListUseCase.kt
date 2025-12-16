package org.example.data.mcp

import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Use case для получения списка инструментов от MCP-сервера
 */
class McpToolsListUseCase {
    private val logger = LoggerFactory.getLogger(McpToolsListUseCase::class.java)

    /**
     * Подключается к MCP-серверу и получает список доступных инструментов
     * @return список инструментов или пустой список в случае ошибки
     */
    fun execute(): List<Tool> {
        val mcpService = McpClientService()
        var serverProcess: java.lang.Process? = null

        return try {
            // Инициализируем клиент
            mcpService.initialize()

            // Запускаем сервер
            serverProcess = McpServerProcess.startServer()

            // Подключаемся через stdio
            runBlocking {
                mcpService.connect(serverProcess)

                // Получаем список инструментов
                val tools = mcpService.listTools()

                // Закрываем соединение
                mcpService.disconnect()

                tools
            }
        } catch (e: Exception) {
            logger.error("Ошибка при выполнении McpToolsListUseCase: ${e.message}", e)
            emptyList()
        } finally {
            // Останавливаем процесс сервера
            serverProcess?.let { McpServerProcess.stopServer(it) }
        }
    }
}

