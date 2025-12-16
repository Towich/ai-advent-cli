package org.example

import org.example.data.mcp.McpToolsListUseCase
import org.slf4j.LoggerFactory

/**
 * Утилита для тестирования MCP подключения и получения списка инструментов
 * Запуск: можно вызвать из main или создать отдельную точку входа
 */
object McpTest {
    private val logger = LoggerFactory.getLogger(McpTest::class.java)

    /**
     * Тестирует подключение к MCP-серверу и выводит список инструментов в консоль
     */
    fun testMcpConnection() {
        println("=== Тестирование MCP подключения ===")
        println("Запуск MCP-сервера и получение списка инструментов...")
        println()

        val useCase = McpToolsListUseCase()
        val tools = useCase.execute()

        if (tools.isEmpty()) {
            println("❌ Не удалось получить инструменты или список пуст")
            logger.warn("Список инструментов пуст")
            return
        }

        println("✅ Успешно получено инструментов: ${tools.size}")
        println()
        println("=== Список доступных инструментов ===")

        tools.forEachIndexed { index, tool ->
            val toolName = tool.name ?: "Без имени"
            val toolDescription = tool.description
            println("${index + 1}. $toolName")
            toolDescription?.let { description ->
                println("   Описание: $description")
            }
            println()
        }

        println("=== Тест завершен ===")
    }
}

// Раскомментируйте для запуска отдельно:
// fun main() {
//     McpTest.testMcpConnection()
// }

