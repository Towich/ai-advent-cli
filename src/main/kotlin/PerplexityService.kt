package org.example

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.dto.ChatApiRequest

@Serializable
data class Message(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val max_tokens: Int = 256,
    val disable_search: Boolean = true,
    val messages: List<Message>,
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>,
    val model: String? = null
)

@Serializable
data class Choice(
    val message: Message
)

class PerplexityService {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        
        // Настройка таймаутов (в миллисекундах)
        engine {
            requestTimeout = 60000 // 60 секунд
        }
    }
    
    private fun buildSystemPrompt(request: ChatApiRequest): String? {
        val parts = mutableListOf<String>()

        // Добавляем пользовательский системный промпт, если есть
        request.systemPrompt?.let { parts.add(it) }
        
        // Добавляем формат вывода, если указан
        request.outputFormat?.let { format ->
            parts.add(buildFormatSystemPrompt(format))
            
            // Если формат JSON и есть схема, добавляем схему
            if (format.lowercase() == "json" && request.outputSchema != null) {
                parts.add("Схема JSON: ${request.outputSchema}")
            }
        }
        
        return if (parts.isNotEmpty()) {
            parts.joinToString("\n\n")
        } else {
            null
        }
    }
    
    private fun buildSystemPromptWithRoundContext(
        baseSystemPrompt: String?,
        isLastRound: Boolean,
        initialUserMessage: String?,
        currentRound: Int,
        maxRounds: Int
    ): String? {
        val parts = mutableListOf<String>()
        
        // Добавляем базовый системный промпт
        if (baseSystemPrompt != null) {
            parts.add(baseSystemPrompt)
        }
        
        if (isLastRound) {
            // Добавляем специальную инструкцию для последнего раунда
            val lastRoundInstruction = buildString {
                append("\n\nВАЖНО: Это последний раунд диалога (раунд $currentRound из $maxRounds).")
                if (initialUserMessage != null) {
                    append("\nПервоначальный запрос пользователя был: \"$initialUserMessage\"")
                }
                append("\n\nТвоя задача:")
                append("\n1. Собрать всю информацию, полученную в предыдущих раундах диалога")
                append("\n2. Проанализировать все ответы пользователя на твои вопросы")
                append("\n3. Дать полный, исчерпывающий и структурированный ответ на первоначальный запрос пользователя")
                append("\n4. Не задавай новых вопросов - это финальный ответ")
                append("\n\nОтвет должен быть максимально полным и полезным, учитывая всю собранную информацию.")
            }
            parts.add(lastRoundInstruction)
        } else {
            // Добавляем информацию о текущем раунде
            parts.add("\nТекущий раунд: $currentRound из $maxRounds. Отвечай только одним вопросом в этом раунде")
        }
        
        return if (parts.isNotEmpty()) {
            parts.joinToString("\n\n")
        } else {
            null
        }
    }
    
    private fun buildMessagesHistory(
        systemPrompt: String?,
        sessionMessages: List<Message>,
        newUserMessage: String
    ): List<Message> {
        val messages = mutableListOf<Message>()
        
        // Добавляем системное сообщение, если есть
        systemPrompt?.let { prompt ->
            messages.add(Message(role = "system", content = prompt))
        }
        
        // Добавляем все сообщения из сессии (кроме системного, если оно там было)
        // Системное сообщение мы уже добавили выше с учетом контекста раунда
        sessionMessages.forEach { message ->
            if (message.role != "system") {
                messages.add(message)
            }
        }
        
        // Добавляем новое пользовательское сообщение
        messages.add(Message(role = "user", content = newUserMessage))
        
        return messages
    }

    private fun buildFormatSystemPrompt(format: String): String {
        return """
            Ты — ассистент, который отвечает исключительно в формате $format без какой-либо дополнительной информации, пояснений или текста. 
            Каждый ответ должен быть валидным $format-объектом, содержащим только необходимую структурированную информацию по запросу. 
            Никакого свободного текста, описаний, комментариев или объяснений не добавляй.
            Также не добавляй служебных символов, по типу "```json"
            Не используй знаки переноса строк ('\n') и не переноси текст на д, пиши всё сплошным текстом
        """.trimIndent()
    }

    private fun validateJsonResponse(content: String): Boolean {
        return try {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            json.decodeFromString<JsonObject>(content.trim())
            true
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun sendMessage(
        request: ChatApiRequest,
        session: DialogSession? = null,
        isLastRound: Boolean = false
    ): Result<Pair<String, String>> {
        return try {
            val model = session?.model ?: (request.model ?: Config.model)
            val maxTokens = session?.maxTokens ?: (request.maxTokens ?: 256)
            val disableSearch = session?.disableSearch ?: (request.disableSearch ?: true)
            
            val messages: List<Message>
            val systemPrompt: String?
            
            if (session != null) {
                // Работаем с сессией - используем историю сообщений
                val baseSystemPrompt = session.systemPrompt
                
                // Формируем системный промпт с учетом контекста раунда
                // currentRound - количество завершенных раундов, следующий будет currentRound + 1
                val nextRound = session.currentRound + 1
                systemPrompt = if (isLastRound) {
                    buildSystemPromptWithRoundContext(
                        baseSystemPrompt = baseSystemPrompt,
                        isLastRound = true,
                        initialUserMessage = session.initialUserMessage,
                        currentRound = nextRound,
                        maxRounds = session.maxRounds
                    )
                } else {
                    // Для не последнего раунда добавляем информацию о раунде
                    buildSystemPromptWithRoundContext(
                        baseSystemPrompt = baseSystemPrompt,
                        isLastRound = false,
                        initialUserMessage = null,
                        currentRound = nextRound,
                        maxRounds = session.maxRounds
                    ) ?: baseSystemPrompt
                }
                
                // Добавляем формат вывода, если указан
                val finalSystemPrompt = if (request.outputFormat != null) {
                    val parts = mutableListOf<String>()
                    systemPrompt?.let { parts.add(it) }
                    
                    request.outputFormat?.let { format ->
                        parts.add(buildFormatSystemPrompt(format))
                        
                        if (format.lowercase() == "json" && request.outputSchema != null) {
                            parts.add("Схема JSON: ${request.outputSchema}")
                        }
                    }
                    
                    if (parts.isNotEmpty()) {
                        parts.joinToString("\n\n")
                    } else {
                        null
                    }
                } else {
                    systemPrompt
                }
                
                // Формируем историю сообщений
                messages = buildMessagesHistory(
                    systemPrompt = finalSystemPrompt,
                    sessionMessages = session.messages,
                    newUserMessage = request.message
                )
            } else {
                // Работаем без сессии - обычный режим
                val baseSystemPrompt = buildSystemPrompt(request)
                
                messages = mutableListOf<Message>().apply {
                    baseSystemPrompt?.let { prompt ->
                        add(Message(role = "system", content = prompt))
                    }
                    add(Message(role = "user", content = request.message))
                }
            }
            
            // Логируем информацию о запросе
            val totalMessages = messages.size
            val totalChars = messages.sumOf { it.content.length }
            println("Отправка запроса к Perplexity API:")
            println("  - Количество сообщений: $totalMessages")
            println("  - Общее количество символов: $totalChars")
            println("  - Модель: $model")
            println("  - Max tokens: $maxTokens")
            if (session != null) {
                println("  - Сессия: ${session.sessionId}, раунд: ${session.currentRound + 1}/${session.maxRounds}, последний: $isLastRound")
            }
            
            val httpResponse = client.post(Config.apiUrl) {
                header(HttpHeaders.Authorization, "Bearer ${Config.apiKey}")
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = model,
                        max_tokens = maxTokens,
                        disable_search = disableSearch,
                        messages = messages
                    )
                )
            }
            
            val statusCode = httpResponse.status.value
            if (statusCode !in 200..299) {
                val errorBody = try {
                    httpResponse.bodyAsText()
                } catch (e: Exception) {
                    "Не удалось прочитать тело ответа: ${e.message}"
                }
                val errorMessage = when (statusCode) {
                    401 -> "Неверный API ключ. Проверьте переменную окружения PERPLEXITY_API_KEY"
                    429 -> "Превышен лимит запросов. Попробуйте позже"
                    in 500..599 -> "Проблема на стороне сервера Perplexity API (HTTP $statusCode): $errorBody"
                    else -> "Ошибка при запросе к Perplexity API (HTTP $statusCode): $errorBody"
                }
                println("Ошибка Perplexity API: $errorMessage")
                return Result.failure(Exception("HTTP $statusCode: $errorMessage"))
            }
            
            val response: ChatResponse = httpResponse.body()
            val content = response.choices.firstOrNull()?.message?.content
            println("Ответ получен, длина контента: ${content?.length ?: 0}")
            if (content != null) {
                // Валидируем JSON, если требуется
                if (request.outputFormat?.lowercase() == "json" && !validateJsonResponse(content)) {
                    return Result.failure(Exception("Ответ от Perplexity API не является валидным JSON"))
                }
                Result.success(Pair(content, response.model ?: model))
            } else {
                Result.failure(Exception("Пустой ответ от Perplexity API"))
            }
        } catch (e: Exception) {
            // Логируем полную информацию об ошибке для отладки
            println("Исключение при запросе к Perplexity API: ${e.javaClass.simpleName}")
            println("Сообщение: ${e.message}")
            e.printStackTrace()
            
            val errorMessage = when {
                e.message?.contains("401") == true || e.message?.contains("HTTP 401") == true -> 
                    "Неверный API ключ. Проверьте переменную окружения PERPLEXITY_API_KEY"
                e.message?.contains("429") == true || e.message?.contains("HTTP 429") == true -> 
                    "Превышен лимит запросов. Попробуйте позже"
                e.message?.contains("500") == true || e.message?.contains("HTTP 5") == true -> 
                    "Проблема на стороне сервера Perplexity API: ${e.message}"
                e.message?.contains("timeout") == true || e.message?.contains("Timeout") == true ->
                    "Таймаут при запросе к Perplexity API. Возможно, запрос слишком большой или сервер перегружен"
                else -> "Ошибка при запросе к Perplexity API: ${e.message ?: e.javaClass.simpleName}"
            }
            Result.failure(Exception(errorMessage))
        }
    }
    
    fun close() {
        client.close()
    }
}

