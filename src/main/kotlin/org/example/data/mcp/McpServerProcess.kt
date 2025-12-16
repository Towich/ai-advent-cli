package org.example.data.mcp

import org.example.infrastructure.config.AppConfig
import org.slf4j.LoggerFactory

/**
 * Утилита для запуска MCP-сервера как процесса
 */
object McpServerProcess {
    private val logger = LoggerFactory.getLogger(McpServerProcess::class.java)
    
    /**
     * Запускает MCP-сервер "everything" как процесс
     * @return Process объект запущенного сервера
     */
    fun startServer(): Process {
        val command = AppConfig.mcpServerCommand
        
        logger.info("Запуск MCP-сервера командой: ${command.joinToString(" ")}")
        
        val processBuilder = java.lang.ProcessBuilder(command)
        
        // Перенаправляем stderr в stdout для удобства логирования
        processBuilder.redirectErrorStream(true)
        
        // Наследуем переменные окружения
        processBuilder.environment().putAll(System.getenv())
        
        val process = processBuilder.start()
        
        logger.info("MCP-сервер запущен, PID: ${process.pid()}")
        
        return process
    }
    
    /**
     * Останавливает процесс сервера
     */
    fun stopServer(process: Process) {
        try {
            if (process.isAlive) {
                logger.info("Остановка MCP-сервера, PID: ${process.pid()}")
                process.destroy()
                
                // Ждем завершения процесса (максимум 5 секунд)
                val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    logger.warn("Процесс не завершился за 5 секунд, принудительное завершение")
                    process.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            logger.error("Ошибка при остановке MCP-сервера: ${e.message}", e)
        }
    }
}

