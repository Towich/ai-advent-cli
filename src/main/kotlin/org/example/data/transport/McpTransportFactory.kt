package org.example.data.transport

import org.slf4j.LoggerFactory

/**
 * Фабрика для создания транспортов MCP
 */
object McpTransportFactory {
    private val logger = LoggerFactory.getLogger(McpTransportFactory::class.java)

    /**
     * Создает транспорт на основе конфигурации
     * 
     * @param config строка конфигурации в формате:
     *   - "http://..." или "https://..." - HTTP транспорт
     *   - "stdio://command arg1 arg2" - stdio транспорт с командой
     *   - "stdio:command arg1 arg2" - альтернативный формат stdio
     * 
     * @return созданный транспорт
     */
    fun create(config: String): McpTransport {
        return when {
            config.startsWith("http://") || config.startsWith("https://") -> {
                logger.info("Создание HTTP транспорта для: $config")
                HttpMcpTransport(config)
            }
            
            config.startsWith("stdio://") -> {
                val command = config.removePrefix("stdio://").split(" ").filter { it.isNotBlank() }
                if (command.isEmpty()) {
                    throw IllegalArgumentException("Команда для stdio транспорта не указана")
                }
                logger.info("Создание stdio транспорта для команды: ${command.joinToString(" ")}")
                StdioMcpTransport(command)
            }
            
            config.startsWith("stdio:") -> {
                val command = config.removePrefix("stdio:").split(" ").filter { it.isNotBlank() }
                if (command.isEmpty()) {
                    throw IllegalArgumentException("Команда для stdio транспорта не указана")
                }
                logger.info("Создание stdio транспорта для команды: ${command.joinToString(" ")}")
                StdioMcpTransport(command)
            }
            
            else -> {
                // По умолчанию считаем HTTP
                logger.info("Неизвестный формат конфигурации, используем HTTP транспорт: $config")
                HttpMcpTransport(config)
            }
        }
    }
}



