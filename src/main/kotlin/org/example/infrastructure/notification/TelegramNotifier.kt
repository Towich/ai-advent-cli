package org.example.infrastructure.notification

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class TelegramNotifier(
    private val botToken: String,
    private val chatId: String,
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
        }
    }
) {
    @Serializable
    private data class TelegramResponse(
        val ok: Boolean,
        val result: JsonElement? = null,
        val description: String? = null,
        @SerialName("error_code") val errorCode: Int? = null
    )

    suspend fun sendText(text: String, parseMode: String? = null, disableWebPreview: Boolean = true): Result<Unit> {
        return try {
            val url = URLBuilder("https://api.telegram.org")
                .appendPathSegments("bot$botToken", "sendMessage")
                .build()

            val resp: TelegramResponse = httpClient.post(url) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("chat_id", chatId)
                            append("text", text)
                            if (!parseMode.isNullOrBlank()) append("parse_mode", parseMode)
                            append("disable_web_page_preview", disableWebPreview.toString())
                        }
                    )
                )
            }.body<TelegramResponse>()

            if (!resp.ok) {
                Result.failure(
                    IllegalStateException(
                        "Telegram sendMessage failed: ${resp.errorCode ?: "N/A"} ${resp.description ?: "unknown error"}"
                    )
                )
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        httpClient.close()
    }
}


