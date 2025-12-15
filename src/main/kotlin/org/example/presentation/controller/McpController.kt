package org.example.presentation.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import org.example.data.repository.McpRepositoryImpl
import org.example.presentation.dto.ErrorResponse
import org.example.presentation.dto.McpCallToolRequest
import org.example.presentation.dto.McpCallToolResponse
import org.example.presentation.dto.McpListToolsRequest
import org.example.presentation.dto.McpToolResponse
import org.example.presentation.dto.McpToolsListResponse
import org.slf4j.LoggerFactory

/**
 * Контроллер для обработки запросов к MCP-серверу
 */
class McpController {
    private val logger = LoggerFactory.getLogger(McpController::class.java)
    
    fun configureRoutes(routing: Routing) {
        routing {
            post("/api/mcp/tools") {
                handleListToolsRequest(call)
            }
            post("/api/mcp/tools/call") {
                handleCallToolRequest(call)
            }
        }
    }
    
    private suspend fun handleListToolsRequest(call: ApplicationCall) {
        var mcpRepository: McpRepositoryImpl? = null
        try {
            // Получаем URL MCP сервера из body запроса
            val request = call.receive<McpListToolsRequest>()
            
            // Валидация
            if (request.mcpServerUrl.isBlank()) {
                logger.warn("Ошибка валидации: URL MCP сервера пустой")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = "URL MCP сервера не может быть пустым",
                        code = "INVALID_REQUEST"
                    )
                )
                return
            }
            
            logger.info("Запрос списка инструментов MCP, URL: ${request.mcpServerUrl}")
            
            // Создаем репозиторий с URL из запроса
            mcpRepository = McpRepositoryImpl(serverUrl = request.mcpServerUrl)
            
            val result = mcpRepository.listTools()
            
            result.fold(
                onSuccess = { tools ->
                    // Конвертируем domain модели в DTO
                    val toolResponses = tools.map { tool ->
                        // Конвертируем JsonElement в строку для inputSchema
                        val inputSchemaString = tool.inputSchema?.mapValues { 
                            it.value.toString() 
                        }
                        
                        McpToolResponse(
                            name = tool.name,
                            description = tool.description,
                            inputSchema = inputSchemaString
                        )
                    }
                    
                    logger.info("Успешно получено инструментов: ${toolResponses.size}")
                    
                    call.respond(
                        HttpStatusCode.OK,
                        McpToolsListResponse(
                            tools = toolResponses,
                            count = toolResponses.size
                        )
                    )
                },
                onFailure = { error ->
                    logger.error("Ошибка при получении списка инструментов MCP: ${error.message}", error)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            error = "Ошибка при получении списка инструментов: ${error.message}",
                            code = "MCP_ERROR"
                        )
                    )
                }
            )
        } catch (e: Exception) {
            logger.error("Необработанное исключение при обработке запроса MCP: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "Ошибка при обработке запроса: ${e.message}",
                    code = "INTERNAL_ERROR"
                )
            )
        } finally {
            // Закрываем репозиторий после использования
            mcpRepository?.close()
        }
    }
    
    private suspend fun handleCallToolRequest(call: ApplicationCall) {
        var mcpRepository: McpRepositoryImpl? = null
        try {
            val request = call.receive<McpCallToolRequest>()
            
            logger.info("Запрос на вызов инструмента MCP: toolName=${request.toolName}, arguments=${request.arguments}, mcpServerUrl=${request.mcpServerUrl}")
            
            // Валидация
            if (request.toolName.isBlank()) {
                logger.warn("Ошибка валидации: имя инструмента пустое")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = "Имя инструмента не может быть пустым",
                        code = "INVALID_REQUEST"
                    )
                )
                return
            }
            
            if (request.mcpServerUrl.isBlank()) {
                logger.warn("Ошибка валидации: URL MCP сервера пустой")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = "URL MCP сервера не может быть пустым",
                        code = "INVALID_REQUEST"
                    )
                )
                return
            }
            
            // Создаем репозиторий с URL из запроса
            mcpRepository = McpRepositoryImpl(serverUrl = request.mcpServerUrl)
            
            val result = mcpRepository.callTool(request.toolName, request.arguments)
            
            result.fold(
                onSuccess = { toolResult ->
                    logger.info("Инструмент ${request.toolName} выполнен успешно")
                    
                    call.respond(
                        HttpStatusCode.OK,
                        McpCallToolResponse(
                            toolName = request.toolName,
                            result = toolResult,
                            success = true
                        )
                    )
                },
                onFailure = { error ->
                    logger.error("Ошибка при вызове инструмента ${request.toolName}: ${error.message}", error)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            error = "Ошибка при вызове инструмента: ${error.message}",
                            code = "TOOL_CALL_ERROR"
                        )
                    )
                }
            )
        } catch (e: Exception) {
            logger.error("Необработанное исключение при вызове инструмента: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "Ошибка при обработке запроса: ${e.message}",
                    code = "INTERNAL_ERROR"
                )
            )
        } finally {
            // Закрываем репозиторий после использования
            mcpRepository?.close()
        }
    }
}
