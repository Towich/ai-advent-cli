package org.example

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
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
    
    suspend fun sendMessage(request: ChatApiRequest): Result<Pair<String, String>> {
        return try {
            val model = request.model ?: Config.model
            val maxTokens = request.maxTokens ?: 256
            val disableSearch = request.disableSearch ?: true
            
            // Формируем список сообщений
            val messages = mutableListOf<Message>()
            
            // Добавляем системное сообщение, если есть
            buildSystemPrompt(request)?.let { systemPrompt ->
                messages.add(Message(role = "system", content = systemPrompt))
            }
            
            // Добавляем пользовательское сообщение
            messages.add(Message(role = "user", content = request.message))
            
            val response: ChatResponse = client.post(Config.apiUrl) {
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
            }.body()
            
            val content = response.choices.firstOrNull()?.message?.content
            println(content)
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
            val errorMessage = when {
                e.message?.contains("401") == true -> 
                    "Неверный API ключ. Проверьте переменную окружения PERPLEXITY_API_KEY"
                e.message?.contains("429") == true -> 
                    "Превышен лимит запросов. Попробуйте позже"
                e.message?.contains("500") == true -> 
                    "Проблема на стороне сервера Perplexity API"
                else -> "Ошибка при запросе к Perplexity API: ${e.message}"
            }
            Result.failure(Exception(errorMessage))
        }
    }
    
    fun close() {
        client.close()
    }
}

