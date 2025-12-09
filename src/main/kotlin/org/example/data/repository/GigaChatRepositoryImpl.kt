package org.example.data.repository

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.data.remote.dto.GigaChatMessage
import org.example.data.remote.dto.GigaChatOAuthResponse
import org.example.data.remote.dto.GigaChatRequest
import org.example.data.remote.dto.GigaChatResponse
import org.example.domain.model.Message
import org.example.domain.repository.GigaChatRepository
import org.slf4j.LoggerFactory
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.withLock

/**
 * Реализация репозитория для работы с GigaChat API
 */
class GigaChatRepositoryImpl(
    private val apiUrl: String,
    private val authorizationKey: String
) : GigaChatRepository {
    private val logger = LoggerFactory.getLogger(GigaChatRepositoryImpl::class.java)
    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    // URL для получения Access token
    private val oauthUrl = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"

    // Кэш для Access token
    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0
    private val tokenLock = ReentrantLock()

    // Время жизни токена в миллисекундах (30 минут = 1800000 мс, но вычитаем 2 минуты для безопасности)
    private val tokenLifetimeMs = 28 * 60 * 1000L

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }

        engine {
            requestTimeout = 60000 // 60 секунд

            // Настройка SSL для работы с GigaChat API
            // Используем TrustManager, который принимает все сертификаты
            // Это необходимо для работы с API Sberbank
            https {
                trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            }
        }
    }

    /**
     * Получает Access token через OAuth API
     * Токен кэшируется и обновляется при необходимости
     */
    private suspend fun getAccessToken(): Result<String> {
        // Проверяем кэш вне блокировки (чтение volatile переменных безопасно)
        val now = System.currentTimeMillis()
        val cachedToken = cachedAccessToken
        val expiresAt = tokenExpiresAt

        if (cachedToken != null && now < expiresAt) {
            logger.debug("Используется кэшированный Access token")
            return Result.success(cachedToken)
        }

        // Токен истек или отсутствует, получаем новый
        // Делаем это вне блокировки, чтобы не блокировать другие потоки
        logger.info("Получение нового Access token через OAuth")

        val tokenResult = try {
            // Генерируем уникальный RqUID
            val rqUID = UUID.randomUUID().toString()

            // Кодируем Authorization key в Base64 для Basic аутентификации
            val credentials = Base64.getEncoder().encodeToString(authorizationKey.toByteArray())

            val httpResponse = client.submitForm(
                url = oauthUrl,
                formParameters = parameters {
                    append("scope", "GIGACHAT_API_PERS")
                }
            ) {
                header(HttpHeaders.Accept, ContentType.Application.Json)
                header("RqUID", rqUID)
                header(HttpHeaders.Authorization, "Basic $credentials")
            }

            val statusCode = httpResponse.status.value
            logger.info("GigaChat OAuth Response:")
            logger.info("  Status Code: $statusCode")

            if (statusCode !in 200..299) {
                val errorBody = try {
                    httpResponse.bodyAsText()
                } catch (e: Exception) {
                    "Не удалось прочитать тело ответа: ${e.message}"
                }
                logger.error("  Error Body: $errorBody")
                logger.error("  Response Headers: ${httpResponse.headers}")

                val errorMessage = when (statusCode) {
                    401 -> "Неверный Authorization key. Проверьте переменную окружения GIGACHAT_API_KEY"
                    400 -> "Неверный запрос к OAuth API (HTTP 400): $errorBody"
                    else -> "Ошибка при получении Access token (HTTP $statusCode): $errorBody"
                }
                logger.error("Ошибка получения Access token: $errorMessage")
                return Result.failure(Exception(errorMessage))
            }

            val oauthResponse: GigaChatOAuthResponse = httpResponse.body()
            val accessToken = oauthResponse.access_token

            // Обновляем кэш внутри блокировки
            tokenLock.withLock {
                cachedAccessToken = accessToken
                tokenExpiresAt = now + tokenLifetimeMs
            }

            logger.info("Access token успешно получен и кэширован")
            Result.success(accessToken)
        } catch (e: Exception) {
            logger.error("Исключение при получении Access token: ${e.message}", e)
            Result.failure(Exception("Ошибка при получении Access token: ${e.message}"))
        }

        return tokenResult
    }

    override suspend fun sendMessage(
        messages: List<Message>,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        temperature: Double?
    ): Result<Pair<String, String>> {
        return try {
            // Получаем Access token
            val accessTokenResult = getAccessToken()
            val accessToken = accessTokenResult.getOrElse { error ->
                return Result.failure(error)
            }

            // Конвертируем domain модели в DTO
            val gigaChatMessages = messages.map { message ->
                GigaChatMessage(role = message.role, content = message.content)
            }

            // Создаем объект запроса (disableSearch игнорируется для GigaChat)
            val request = GigaChatRequest(
                model = model,
                max_tokens = maxTokens.takeIf { it > 0 },
                messages = gigaChatMessages,
                temperature = temperature
            )

            // Логируем эндпоинт и тело запроса
            val requestBodyJson = jsonSerializer.encodeToString(request)
            logger.info("GigaChat API Request:")
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
                header(HttpHeaders.Authorization, "Bearer $accessToken")
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
                    400 -> "Неверный запрос к GigaChat API (HTTP 400): $errorBody"
                    401 -> {
                        // Если токен невалиден, очищаем кэш и пробуем получить новый
                        tokenLock.withLock {
                            cachedAccessToken = null
                            tokenExpiresAt = 0
                        }
                        "Неверный Access token. Попробуйте повторить запрос"
                    }

                    404 -> "Ресурс не найден в GigaChat API (HTTP 404): $errorBody"
                    422 -> "Ошибка валидации запроса к GigaChat API (HTTP 422): $errorBody"
                    429 -> "Превышен лимит запросов. Попробуйте позже"
                    in 500..599 -> "Проблема на стороне сервера GigaChat API (HTTP $statusCode): $errorBody"
                    else -> "Ошибка при запросе к GigaChat API (HTTP $statusCode): $errorBody"
                }
                println("Ошибка GigaChat API: $errorMessage")
                return Result.failure(Exception("HTTP $statusCode: $errorMessage"))
            }

            val response: GigaChatResponse = httpResponse.body()
            val content = response.choices.firstOrNull()?.message?.content
            println("Ответ получен, длина контента: ${content?.length ?: 0}")

            if (content != null) {
                // Нормализуем модель из ответа или используем исходную
                val responseModel = response.model?.let { normalizeModel(it) } ?: model
                Result.success(Pair(content, responseModel))
            } else {
                Result.failure(Exception("Пустой ответ от GigaChat API"))
            }
        } catch (e: Exception) {
            println("Исключение при запросе к GigaChat API: ${e.javaClass.simpleName}")
            println("Сообщение: ${e.message}")
            e.printStackTrace()

            val errorMessage = when {
                e.message?.contains("401") == true || e.message?.contains("HTTP 401") == true ->
                    "Неверный Authorization key или Access token. Проверьте переменную окружения GIGACHAT_API_KEY"

                e.message?.contains("429") == true || e.message?.contains("HTTP 429") == true ->
                    "Превышен лимит запросов. Попробуйте позже"

                e.message?.contains("400") == true || e.message?.contains("HTTP 400") == true ->
                    "Неверный запрос к GigaChat API: ${e.message}"

                e.message?.contains("404") == true || e.message?.contains("HTTP 404") == true ->
                    "Ресурс не найден в GigaChat API: ${e.message}"

                e.message?.contains("422") == true || e.message?.contains("HTTP 422") == true ->
                    "Ошибка валидации запроса к GigaChat API: ${e.message}"

                e.message?.contains("500") == true || e.message?.contains("HTTP 5") == true ->
                    "Проблема на стороне сервера GigaChat API: ${e.message}"

                e.message?.contains("timeout") == true || e.message?.contains("Timeout") == true ->
                    "Таймаут при запросе к GigaChat API. Возможно, запрос слишком большой или сервер перегружен"

                else -> "Ошибка при запросе к GigaChat API: ${e.message ?: e.javaClass.simpleName}"
            }
            Result.failure(Exception(errorMessage))
        }
    }

    /**
     * Нормализует модель из ответа GigaChat (например, "GigaChat:1.0.26.20" -> "GigaChat-2")
     */
    private fun normalizeModel(modelFromResponse: String): String {
        // Если модель в формате "GigaChat:1.0.26.20", пытаемся извлечь базовое имя
        return if (modelFromResponse.startsWith("GigaChat:")) {
            // Можно вернуть исходную модель из запроса, но для простоты возвращаем базовое имя
            "GigaChat-2"
        } else {
            modelFromResponse
        }
    }

    override fun close() {
        client.close()
    }
}
