package org.example.domain.usecase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.data.repository.McpRepositoryImpl
import org.example.domain.model.Message
import org.example.domain.model.McpTool
import org.example.domain.model.TokenUsage
import org.example.domain.repository.GigaChatRepository
import org.example.domain.repository.HuggingFaceRepository
import org.example.domain.repository.PerplexityRepository
import org.example.infrastructure.config.Vendor
import org.example.infrastructure.config.VendorDetector
import org.example.presentation.dto.ToolCallInfo
import org.slf4j.LoggerFactory

/**
 * Use Case для отправки сообщения в чат с поддержкой MCP-тулзов
 */
class SendChatMessageWithToolsUseCase(
    private val perplexityRepository: PerplexityRepository,
    private val gigaChatRepository: GigaChatRepository,
    private val huggingFaceRepository: HuggingFaceRepository,
    private val defaultModel: String,
    private val defaultMaxTokens: Int
) {
    private val logger = LoggerFactory.getLogger(SendChatMessageWithToolsUseCase::class.java)
    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Результат выполнения UseCase
     */
    data class ChatWithToolsResult(
        val content: String,
        val model: String,
        val executionTimeMs: Long,
        val usage: TokenUsage?,
        val toolCalls: List<ToolCallInfo>,
        val totalToolIterations: Int
    )

    /**
     * Выполняет запрос к чату с поддержкой MCP-тулзов
     */
    suspend fun execute(
        message: String,
        vendor: String,
        model: String?,
        maxTokens: Int?,
        disableSearch: Boolean?,
        systemPrompt: String?,
        outputFormat: String?,
        outputSchema: String?,
        temperature: Double?,
        mcpServerUrls: List<String>,
        maxToolIterations: Int,
        onToolCall: (suspend (ToolCallInfo) -> Unit)? = null
    ): Result<ChatWithToolsResult> {
        val startTime = System.currentTimeMillis()
        val mcpRepositories = mutableMapOf<String, McpRepositoryImpl>()
        
        return try {
            // Создаем MCP репозитории для каждого сервера
            mcpServerUrls.forEach { serverUrl ->
                try {
                    val repository = McpRepositoryImpl(serverUrl = serverUrl)
                    mcpRepositories[serverUrl] = repository
                    logger.info("Создан MCP репозиторий для сервера: $serverUrl")
                } catch (e: Exception) {
                    logger.error("Ошибка при создании репозитория для сервера $serverUrl: ${e.message}", e)
                }
            }
            
            if (mcpRepositories.isEmpty()) {
                return Result.failure(Exception("Не удалось создать ни одного MCP репозитория"))
            }
            
            // Получаем список тулзов от всех серверов
            val allTools = mutableListOf<McpTool>()
            val failedServers = mutableListOf<String>()
            
            mcpRepositories.forEach { (serverUrl, repository) ->
                try {
                    val toolsResult = repository.listTools()
                    toolsResult.fold(
                        onSuccess = { tools ->
                            // Добавляем информацию о сервере к каждому инструменту
                            val toolsWithServer = tools.map { tool ->
                                tool.copy(serverUrl = serverUrl)
                            }
                            allTools.addAll(toolsWithServer)
                            logger.info("Получено ${tools.size} тулзов от MCP-сервера: $serverUrl")
                        },
                        onFailure = { error ->
                            logger.error("Ошибка при получении списка тулзов от сервера $serverUrl: ${error.message}")
                            failedServers.add(serverUrl)
                        }
                    )
                } catch (e: Exception) {
                    logger.error("Исключение при получении тулзов от сервера $serverUrl: ${e.message}", e)
                    failedServers.add(serverUrl)
                }
            }
            
            // Если все серверы не удались, возвращаем ошибку
            if (allTools.isEmpty()) {
                val errorMsg = if (failedServers.size == mcpServerUrls.size) {
                    "Не удалось получить тулзы ни от одного MCP-сервера. Ошибки: ${failedServers.joinToString(", ")}"
                } else {
                    "MCP-серверы не предоставили ни одного тула"
                }
                return Result.failure(Exception(errorMsg))
            }
            
            // Если некоторые серверы не удались, логируем предупреждение, но продолжаем работу
            if (failedServers.isNotEmpty()) {
                logger.warn("Не удалось получить тулзы от некоторых серверов: ${failedServers.joinToString(", ")}. Продолжаем работу с доступными тулзами.")
            }
            
            logger.info("Всего получено ${allTools.size} тулзов от ${mcpRepositories.size - failedServers.size} MCP-серверов")
            val tools = allTools
            
            // Определяем вендора
            val vendorEnum = VendorDetector.parseVendor(vendor)
                ?: return Result.failure(Exception("Неизвестный vendor: $vendor"))
            
            // Определяем модель
            val modelToUse = model ?: defaultModel
            val maxTokensToUse = maxTokens ?: defaultMaxTokens
            val disableSearchToUse = disableSearch ?: true
            
            // Формируем system prompt с описанием тулзов
            val toolsSystemPrompt = buildToolsSystemPrompt(tools, systemPrompt, outputFormat, outputSchema)
            
            // Создаем начальные сообщения
            val messages = mutableListOf<Message>()
            if (toolsSystemPrompt.isNotEmpty()) {
                messages.add(Message(role = Message.ROLE_SYSTEM, content = toolsSystemPrompt))
            }
            messages.add(Message(role = Message.ROLE_USER, content = message))
            
            // Переменные для отслеживания итераций
            var currentIteration = 0
            val toolCalls = mutableListOf<ToolCallInfo>()
            var totalUsage: TokenUsage? = null
            var finalModel: String? = null
            
            // Цикл обработки с тулзами
            while (currentIteration < maxToolIterations) {
                currentIteration++
                logger.info("Итерация $currentIteration/$maxToolIterations")
                
                // Отправляем запрос к модели
                val result = when (vendorEnum) {
                    Vendor.PERPLEXITY -> perplexityRepository.sendMessage(
                        messages, modelToUse, maxTokensToUse, disableSearchToUse, temperature
                    )
                    Vendor.GIGACHAT -> gigaChatRepository.sendMessage(
                        messages, modelToUse, maxTokensToUse, disableSearchToUse, temperature
                    )
                    Vendor.HUGGINGFACE -> huggingFaceRepository.sendMessage(
                        messages, modelToUse, maxTokensToUse, disableSearchToUse, temperature
                    )
                }
                
                val (content, responseModel, usage) = result.getOrElse { error ->
                    logger.error("Ошибка при запросе к модели: ${error.message}")
                    return Result.failure(error)
                }
                
                finalModel = responseModel
                
                // Обновляем общее использование токенов
                totalUsage = combineUsage(totalUsage, usage)
                
                // Пытаемся распарсить ответ как JSON с вызовом тула
                logger.debug("Парсинг ответа модели (итерация $currentIteration): ${content.take(200)}...")
                val toolCall = parseToolCall(content)
                
                if (toolCall != null) {
                    // Это вызов тула
                    logger.info("Обнаружен вызов тула: ${toolCall.toolName} с аргументами: ${toolCall.arguments}")
                    
                    // Находим инструмент и определяем, к какому серверу он принадлежит
                    val tool = tools.find { it.name == toolCall.toolName }
                    val serverUrl = tool?.serverUrl
                    
                    if (serverUrl == null) {
                        logger.error("Не удалось найти сервер для инструмента: ${toolCall.toolName}")
                        val errorToolCallInfo = ToolCallInfo(
                            toolName = toolCall.toolName,
                            arguments = toolCall.arguments,
                            result = "Ошибка: Инструмент не найден ни на одном из подключенных MCP-серверов",
                            success = false,
                            serverUrl = null
                        )
                        toolCalls.add(errorToolCallInfo)
                        onToolCall?.invoke(errorToolCallInfo)
                        messages.add(Message(role = Message.ROLE_ASSISTANT, content = content))
                        messages.add(
                            Message(
                                role = Message.ROLE_USER,
                                content = "Ошибка: Инструмент ${toolCall.toolName} не найден ни на одном из подключенных MCP-серверов. Используйте другой инструмент или предоставьте финальный ответ."
                            )
                        )
                        continue
                    }
                    
                    val repository = mcpRepositories[serverUrl]
                    if (repository == null) {
                        logger.error("Репозиторий для сервера $serverUrl не найден")
                        val errorToolCallInfo = ToolCallInfo(
                            toolName = toolCall.toolName,
                            arguments = toolCall.arguments,
                            result = "Ошибка: Репозиторий для сервера $serverUrl не найден",
                            success = false,
                            serverUrl = serverUrl
                        )
                        toolCalls.add(errorToolCallInfo)
                        onToolCall?.invoke(errorToolCallInfo)
                        messages.add(Message(role = Message.ROLE_ASSISTANT, content = content))
                        messages.add(
                            Message(
                                role = Message.ROLE_USER,
                                content = "Ошибка: Не удалось найти репозиторий для сервера $serverUrl. Используйте другой инструмент или предоставьте финальный ответ."
                            )
                        )
                        continue
                    }
                    
                    logger.info("Выполняю тул ${toolCall.toolName} на сервере: $serverUrl")
                    
                    // Выполняем тул
                    val toolResult = repository.callTool(toolCall.toolName, toolCall.arguments)
                    
                    toolResult.fold(
                        onSuccess = { result ->
                            logger.info("Тул ${toolCall.toolName} выполнен успешно")
                            
                            // Сохраняем информацию о вызове тула
                            val toolCallInfo = ToolCallInfo(
                                toolName = toolCall.toolName,
                                arguments = toolCall.arguments,
                                result = result,
                                success = true,
                                serverUrl = serverUrl
                            )
                            toolCalls.add(toolCallInfo)
                            
                            // Вызываем колбэк для уведомления о туле
                            try {
                                logger.info("Вызываю колбэк onToolCall для тула: ${toolCall.toolName}")
                                onToolCall?.invoke(toolCallInfo)
                                logger.info("Колбэк onToolCall успешно выполнен для тула: ${toolCall.toolName}")
                            } catch (e: Exception) {
                                logger.error("Ошибка при вызове колбэка onToolCall: ${e.message}", e)
                            }
                            
                            // Добавляем ответ модели и результат тула в диалог
                            messages.add(Message(role = Message.ROLE_ASSISTANT, content = content))
                            messages.add(
                                Message(
                                    role = Message.ROLE_USER,
                                    content = "Результат выполнения тула ${toolCall.toolName}: $result\n\nВы можете продолжить использовать другие тулзы, если это необходимо для выполнения задачи. Если у вас достаточно информации для финального ответа, используйте формат {\"final\": \"<ваш ответ>\"}."
                                )
                            )
                        },
                        onFailure = { error ->
                            logger.error("Ошибка при выполнении тула ${toolCall.toolName}: ${error.message}")
                            
                            // Сохраняем информацию об ошибке
                            val errorToolCallInfo = ToolCallInfo(
                                toolName = toolCall.toolName,
                                arguments = toolCall.arguments,
                                result = "Ошибка: ${error.message}",
                                success = false,
                                serverUrl = serverUrl
                            )
                            toolCalls.add(errorToolCallInfo)
                            
                            // Вызываем колбэк для уведомления об ошибке тула
                            try {
                                logger.info("Вызываю колбэк onToolCall для ошибки тула: ${toolCall.toolName}")
                                onToolCall?.invoke(errorToolCallInfo)
                                logger.info("Колбэк onToolCall успешно выполнен для ошибки тула: ${toolCall.toolName}")
                            } catch (e: Exception) {
                                logger.error("Ошибка при вызове колбэка onToolCall для ошибки: ${e.message}", e)
                            }
                            
                            // Добавляем ответ модели и ошибку в диалог
                            messages.add(Message(role = Message.ROLE_ASSISTANT, content = content))
                            messages.add(
                                Message(
                                    role = Message.ROLE_USER,
                                    content = "Ошибка при выполнении тула ${toolCall.toolName}: ${error.message}\n\nВы можете попробовать использовать другой тул или предоставить финальный ответ, используя формат {\"final\": \"<ваш ответ>\"}."
                                )
                            )
                        }
                    )
                } else {
                    // Это финальный ответ, не требующий вызова тула
                    // Извлекаем финальный ответ из JSON, если он там есть
                    logger.info("Модель не вызвала тул в итерации $currentIteration. Завершаем диалог.")
                    logger.debug("Ответ модели (без вызова тула): ${content.take(200)}...")
                    val finalContent = extractFinalAnswer(content)
                    logger.info("Получен финальный ответ от модели (без вызова тула)")
                    val executionTimeMs = System.currentTimeMillis() - startTime
                    
                    return Result.success(
                        ChatWithToolsResult(
                            content = finalContent,
                            model = responseModel,
                            executionTimeMs = executionTimeMs,
                            usage = totalUsage,
                            toolCalls = toolCalls,
                            totalToolIterations = currentIteration
                        )
                    )
                }
            }
            
            // Достигнут лимит итераций
            logger.warn("Достигнут лимит итераций ($maxToolIterations). Возвращаем последний ответ.")
            val lastMessage = messages.lastOrNull { it.role == Message.ROLE_ASSISTANT }?.content
                ?: "Достигнут лимит итераций вызова тулзов"
            
            val executionTimeMs = System.currentTimeMillis() - startTime
            Result.success(
                ChatWithToolsResult(
                    content = lastMessage,
                    model = finalModel ?: modelToUse,
                    executionTimeMs = executionTimeMs,
                    usage = totalUsage,
                    toolCalls = toolCalls,
                    totalToolIterations = currentIteration
                )
            )
        } catch (e: Exception) {
            logger.error("Ошибка при выполнении UseCase: ${e.message}", e)
            Result.failure(e)
        } finally {
            // Закрываем все репозитории
            mcpRepositories.values.forEach { repository ->
                try {
                    repository.close()
                } catch (e: Exception) {
                    logger.error("Ошибка при закрытии репозитория: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Формирует system prompt с описанием доступных тулзов
     */
    private fun buildToolsSystemPrompt(
        tools: List<McpTool>,
        baseSystemPrompt: String?,
        outputFormat: String?,
        outputSchema: String?
    ): String {
        val parts = mutableListOf<String>()
        
        // Базовый системный промпт
        baseSystemPrompt?.let { parts.add(it) }
        
        // Описание тулзов
        val toolsDescription = buildString {
            append("You are an AI agent that can use tools through MCP (Model Context Protocol).\n")
            append("You can chain multiple tool calls in a conversation to accomplish complex tasks.\n\n")
            append("Available tools:\n")
            tools.forEachIndexed { index, tool ->
                append("${index + 1}) ${tool.name}")
                tool.description?.let { append(": $it") }
                tool.serverUrl?.let { append(" [MCP: $it]") }
                append("\n")
                
                // Описываем схему аргументов
                tool.inputSchema?.let { schema ->
                    append("   Arguments: ")
                    val argsDescription = extractArgumentsDescription(schema)
                    append(argsDescription)
                    append("\n")
                }
            }
            append("\n")
            append("IMPORTANT RULES:\n")
            append("1. You can call tools multiple times in a chain. After a tool is executed, you will receive its result and can call another tool if needed.\n")
            append("2. You can use different tools in sequence to accomplish your goal.\n")
            append("3. Continue using tools until you have all the information needed to provide a final answer.\n")
            append("4. Only stop using tools when you have enough information to give a complete answer to the user.\n\n")
            append("RESPONSE FORMAT:\n")
            append("If you need to call a tool, respond with JSON ONLY in this format:\n")
            append("{\"tool\": \"<tool_name>\", \"args\": { ... }}\n")
            append("\n")
            append("If you have enough information and want to provide the final answer (no more tools needed), respond with JSON ONLY in this format:\n")
            append("{\"final\": \"<your final answer>\"}\n")
            append("\n")
            append("CRITICAL: Your response must be valid JSON. Do not include any text before or after the JSON.")
        }
        parts.add(toolsDescription)
        
        // Формат вывода
        outputFormat?.let { format ->
            if (format.lowercase() == "json") {
                parts.add("Output format: JSON")
                outputSchema?.let { schema ->
                    parts.add("JSON Schema: $schema")
                }
            }
        }
        
        return parts.joinToString("\n\n")
    }
    
    /**
     * Извлекает описание аргументов из JSON Schema
     */
    private fun extractArgumentsDescription(schema: Map<String, kotlinx.serialization.json.JsonElement>): String {
        return try {
            // Пытаемся найти properties в схеме (стандартный формат JSON Schema)
            val properties = schema["properties"]?.jsonObject
            if (properties != null) {
                // Извлекаем свойства и их типы
                val argsList = properties.entries.mapNotNull { (propName, propValue) ->
                    val propObj = propValue.jsonObject ?: return@mapNotNull null
                    val type = propObj["type"]?.jsonPrimitive?.content ?: "any"
                    val title = propObj["title"]?.jsonPrimitive?.content
                    val description = propObj["description"]?.jsonPrimitive?.content
                    
                    val argDesc = buildString {
                        append(propName)
                        append(": ")
                        append(type)
                        title?.let { append(" ($it)") }
                        description?.let { append(" - $it") }
                    }
                    argDesc
                }
                
                // Проверяем required поля
                val required = schema["required"]?.jsonArray?.mapNotNull { element ->
                    try {
                        element.jsonPrimitive.content
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()
                
                if (argsList.isEmpty()) {
                    "нет аргументов"
                } else {
                    val requiredStr = if (required.isNotEmpty()) {
                        " (обязательные: ${required.joinToString(", ")})"
                    } else {
                        ""
                    }
                    argsList.joinToString(", ") + requiredStr
                }
            } else {
                // Если нет properties, пытаемся описать схему проще
                val type = schema["type"]?.jsonPrimitive?.content
                if (type != null) {
                    "type: $type"
                } else {
                    "см. схему выше"
                }
            }
        } catch (e: Exception) {
            logger.debug("Ошибка при извлечении описания аргументов: ${e.message}")
            "см. схему выше"
        }
    }
    
    /**
     * Парсит ответ модели и извлекает информацию о вызове тула
     */
    private fun parseToolCall(content: String): ToolCall? {
        return try {
            // Очищаем контент от возможных markdown блоков
            var cleanedContent = content.trim()
            
            // Удаляем markdown блоки кода
            if (cleanedContent.startsWith("```")) {
                val lines = cleanedContent.lines()
                val firstLine = lines.firstOrNull() ?: ""
                if (firstLine.contains("json", ignoreCase = true) || firstLine == "```") {
                    // Удаляем первую строку (```json или ```)
                    cleanedContent = lines.drop(1).joinToString("\n")
                }
                // Удаляем последнюю строку, если это ```
                if (cleanedContent.endsWith("```")) {
                    cleanedContent = cleanedContent.removeSuffix("```").trim()
                }
            }
            
            cleanedContent = cleanedContent.trim()
            
            // Пытаемся найти JSON в тексте (может быть текст до/после JSON)
            val jsonStart = cleanedContent.indexOf('{')
            val jsonEnd = cleanedContent.lastIndexOf('}')
            
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                cleanedContent = cleanedContent.substring(jsonStart, jsonEnd + 1)
            }
            
            val json = jsonSerializer.parseToJsonElement(cleanedContent).jsonObject
            
            // Проверяем, есть ли поле "tool" - это вызов тула
            val toolName = json["tool"]?.jsonPrimitive?.content
            if (toolName != null) {
                val args = json["args"]?.jsonObject?.let { argsObj ->
                    argsObj.entries.associate { (key, value) ->
                        key to try {
                            // Пытаемся получить примитивное значение
                            value.jsonPrimitive.content
                        } catch (e: Exception) {
                            // Если не примитив (массив, объект), сериализуем в JSON строку
                            jsonSerializer.encodeToString(JsonElement.serializer(), value)
                        }
                    }
                } ?: emptyMap()
                
                logger.debug("Успешно распарсен вызов тула: $toolName с ${args.size} аргументами")
                return ToolCall(toolName, args)
            }
            
            // Если нет поля "tool", проверяем, есть ли поле "final" - это явный финальный ответ
            val hasFinal = json["final"] != null
            if (hasFinal) {
                logger.debug("Обнаружено явное поле 'final' в ответе - это финальный ответ")
            } else {
                logger.debug("Ответ является валидным JSON, но не содержит ни 'tool', ни 'final'")
            }
            
            // Если нет поля "tool", это не вызов тула
            null
        } catch (e: Exception) {
            // Если не удалось распарсить как JSON, считаем это финальным ответом
            logger.debug("Ответ не является валидным JSON с вызовом тула: ${e.message}")
            null
        }
    }
    
    /**
     * Извлекает финальный ответ из контента модели
     * Если контент - это JSON с полем "final", извлекает его значение
     * Иначе возвращает контент как есть
     */
    private fun extractFinalAnswer(content: String): String {
        return try {
            // Очищаем контент от возможных markdown блоков
            val cleanedContent = content.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            val json = jsonSerializer.parseToJsonElement(cleanedContent).jsonObject
            
            // Проверяем, есть ли поле "final" - это финальный ответ в JSON формате
            val finalAnswer = json["final"]?.jsonPrimitive?.content
            if (finalAnswer != null) {
                return finalAnswer
            }
            
            // Если нет поля "final", возвращаем контент как есть
            content
        } catch (e: Exception) {
            // Если не удалось распарсить как JSON, возвращаем контент как есть
            logger.debug("Ответ не является JSON: ${e.message}")
            content
        }
    }
    
    /**
     * Объединяет информацию об использовании токенов
     */
    private fun combineUsage(usage1: TokenUsage?, usage2: TokenUsage?): TokenUsage? {
        if (usage1 == null) return usage2
        if (usage2 == null) return usage1
        
        return TokenUsage(
            promptTokens = (usage1.promptTokens ?: 0) + (usage2.promptTokens ?: 0),
            completionTokens = (usage1.completionTokens ?: 0) + (usage2.completionTokens ?: 0),
            totalTokens = (usage1.totalTokens ?: 0) + (usage2.totalTokens ?: 0),
            cost = (usage1.cost ?: 0.0) + (usage2.cost ?: 0.0)
        )
    }
    
    /**
     * Внутренний класс для представления вызова тула
     */
    private data class ToolCall(
        val toolName: String,
        val arguments: Map<String, String>
    )
}

