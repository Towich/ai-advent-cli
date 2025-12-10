package org.example.data.repository

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.data.remote.dto.HuggingFaceMessage
import org.example.data.remote.dto.HuggingFaceRequest
import org.example.data.remote.dto.HuggingFaceResponse
import org.example.domain.model.Message
import org.example.domain.model.TokenUsage
import org.example.domain.repository.HuggingFaceRepository
import org.slf4j.LoggerFactory

/**
 * Реализация репозитория для работы с Hugging Face API
 */
class HuggingFaceRepositoryImpl(
    private val apiUrl: String,
    private val apiKey: String
) : HuggingFaceRepository {
    private val logger = LoggerFactory.getLogger(HuggingFaceRepositoryImpl::class.java)
    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }
        
        engine {
            requestTimeout = 60000 // 60 секунд
        }
    }
    
    override suspend fun sendMessage(
        messages: List<Message>,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        temperature: Double?
    ): Result<Triple<String, String, TokenUsage?>> {
        return try {
            // Конвертируем domain модели в DTO
            val huggingFaceMessages = messages.map { message ->
                HuggingFaceMessage(role = message.role, content = message.content)
            }
            
            // Создаем объект запроса
            val request = HuggingFaceRequest(
                model = model,
                messages = huggingFaceMessages,
                max_tokens = maxTokens.takeIf { it > 0 },
                temperature = temperature
            )
            
            // Логируем эндпоинт и тело запроса
            val requestBodyJson = jsonSerializer.encodeToString(request)
            logger.info("Hugging Face API Request:")
            logger.info("  Endpoint: $apiUrl")
            logger.info("  Request Body:\n$requestBodyJson")
            
            // Логируем дополнительную информацию о запросе
            val totalMessages = messages.size
            val totalChars = messages.sumOf { it.content.length }
            logger.info("  - Количество сообщений: $totalMessages")
            logger.info("  - Общее количество символов: $totalChars")
            logger.info("  - Модель: $model")
            logger.info("  - Max tokens: $maxTokens")
            temperature?.let { logger.info("  - Temperature: $it") }
            
            val httpResponse = client.post(apiUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(request)
            }
            
            val statusCode = httpResponse.status.value
            if (statusCode !in 200..299) {
                val errorBody = try {
                    httpResponse.bodyAsText()
                } catch (e: Exception) {
                    "Не удалось прочитать тело ответа: ${e.message}"
                }
                val errorMessage = when (statusCode) {
                    401 -> "Неверный API ключ. Проверьте переменную окружения HUGGINGFACE_API_KEY"
                    429 -> "Превышен лимит запросов. Попробуйте позже"
                    in 500..599 -> "Проблема на стороне сервера Hugging Face API (HTTP $statusCode): $errorBody"
                    else -> "Ошибка при запросе к Hugging Face API (HTTP $statusCode): $errorBody"
                }
                println("Ошибка Hugging Face API: $errorMessage")
                return Result.failure(Exception("HTTP $statusCode: $errorMessage"))
            }
            
            val response: HuggingFaceResponse = httpResponse.body()
            val content = response.choices.firstOrNull()?.message?.content
            val responseModel = response.model ?: model
            val usage = response.usage?.let {
                TokenUsage(
                    promptTokens = it.prompt_tokens,
                    completionTokens = it.completion_tokens,
                    totalTokens = it.total_tokens
                )
            }
            println("Ответ получен, длина контента: ${content?.length ?: 0}")
            
            if (content != null) {
                Result.success(Triple(content, responseModel, usage))
            } else {
                Result.failure(Exception("Пустой ответ от Hugging Face API"))
            }
        } catch (e: Exception) {
            println("Исключение при запросе к Hugging Face API: ${e.javaClass.simpleName}")
            println("Сообщение: ${e.message}")
            e.printStackTrace()
            
            val errorMessage = when {
                e.message?.contains("401") == true || e.message?.contains("HTTP 401") == true -> 
                    "Неверный API ключ. Проверьте переменную окружения HUGGINGFACE_API_KEY"
                e.message?.contains("429") == true || e.message?.contains("HTTP 429") == true -> 
                    "Превышен лимит запросов. Попробуйте позже"
                e.message?.contains("500") == true || e.message?.contains("HTTP 5") == true -> 
                    "Проблема на стороне сервера Hugging Face API: ${e.message}"
                e.message?.contains("timeout") == true || e.message?.contains("Timeout") == true ->
                    "Таймаут при запросе к Hugging Face API. Возможно, запрос слишком большой или сервер перегружен"
                else -> "Ошибка при запросе к Hugging Face API: ${e.message ?: e.javaClass.simpleName}"
            }
            Result.failure(Exception(errorMessage))
        }
    }
    
    override fun close() {
        client.close()
    }
}
