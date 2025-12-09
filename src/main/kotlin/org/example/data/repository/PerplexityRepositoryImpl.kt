package org.example.data.repository

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.data.remote.dto.PerplexityMessage
import org.example.data.remote.dto.PerplexityRequest
import org.example.data.remote.dto.PerplexityResponse
import org.example.domain.model.Message
import org.example.domain.model.TokenUsage
import org.example.domain.repository.PerplexityRepository
import org.slf4j.LoggerFactory

/**
 * Реализация репозитория для работы с Perplexity API
 */
class PerplexityRepositoryImpl(
    private val apiUrl: String,
    private val apiKey: String
) : PerplexityRepository {
    private val logger = LoggerFactory.getLogger(PerplexityRepositoryImpl::class.java)
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
    
    /**
     * Рассчитывает стоимость запроса на основе модели и количества токенов
     * 
     * @param model название модели Perplexity
     * @param promptTokens количество токенов в промпте
     * @param completionTokens количество токенов в ответе
     * @return стоимость в долларах или null, если модель неизвестна или данные отсутствуют
     */
    private fun calculateCost(model: String, promptTokens: Int?, completionTokens: Int?): Double? {
        if (promptTokens == null || completionTokens == null) {
            return null
        }
        
        // Цены за 1M токенов для каждой модели
        val modelPricing = when (model.lowercase()) {
            "sonar" -> Pair(1.0, 1.0) // $1/1M input, $1/1M output
            "sonar-pro" -> Pair(3.0, 15.0) // $3/1M input, $15/1M output
            "sonar-reasoning" -> Pair(1.0, 5.0) // $1/1M input, $5/1M output
            "sonar-reasoning-pro" -> Pair(2.0, 8.0) // $2/1M input, $8/1M output
            "sonar-deep-research" -> Pair(3.0, 15.0) // Предполагаем как sonar-pro
            else -> return null // Неизвестная модель
        }
        
        val (inputPrice, outputPrice) = modelPricing
        
        // Расчет: (токены / 1_000_000) * цена
        val inputCost = (promptTokens / 1_000_000.0) * inputPrice
        val outputCost = (completionTokens / 1_000_000.0) * outputPrice
        
        return inputCost + outputCost
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
            val perplexityMessages = messages.map { message ->
                PerplexityMessage(role = message.role, content = message.content)
            }
            
            // Создаем объект запроса
            val request = PerplexityRequest(
                model = model,
                max_tokens = maxTokens,
                disable_search = disableSearch,
                messages = perplexityMessages,
                temperature = temperature
            )
            
            // Логируем эндпоинт и тело запроса
            val requestBodyJson = jsonSerializer.encodeToString(request)
            logger.info("Perplexity API Request:")
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
                    401 -> "Неверный API ключ. Проверьте переменную окружения PERPLEXITY_API_KEY"
                    429 -> "Превышен лимит запросов. Попробуйте позже"
                    in 500..599 -> "Проблема на стороне сервера Perplexity API (HTTP $statusCode): $errorBody"
                    else -> "Ошибка при запросе к Perplexity API (HTTP $statusCode): $errorBody"
                }
                println("Ошибка Perplexity API: $errorMessage")
                return Result.failure(Exception("HTTP $statusCode: $errorMessage"))
            }
            
            val response: PerplexityResponse = httpResponse.body()
            val content = response.choices.firstOrNull()?.message?.content
            val responseModel = response.model ?: model
            val usage = response.usage?.let {
                val cost = calculateCost(responseModel, it.prompt_tokens, it.completion_tokens)
                TokenUsage(
                    promptTokens = it.prompt_tokens,
                    completionTokens = it.completion_tokens,
                    totalTokens = it.total_tokens,
                    cost = cost
                )
            }
            println("Ответ получен, длина контента: ${content?.length ?: 0}")
            
            if (content != null) {
                Result.success(Triple(content, responseModel, usage))
            } else {
                Result.failure(Exception("Пустой ответ от Perplexity API"))
            }
        } catch (e: Exception) {
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
    
    override fun close() {
        client.close()
    }
}


