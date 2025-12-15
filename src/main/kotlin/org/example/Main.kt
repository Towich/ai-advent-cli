package org.example

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.example.data.repository.GigaChatRepositoryImpl
import org.example.data.repository.HuggingFaceRepositoryImpl
import org.example.data.repository.PerplexityRepositoryImpl
import org.example.data.repository.SessionRepositoryImpl
import org.example.domain.usecase.CompressDialogHistoryUseCase
import org.example.domain.usecase.SendChatMessageUseCase
import org.example.domain.usecase.SendMultiChatMessageUseCase
import org.example.infrastructure.config.AppConfig
import org.example.presentation.controller.ChatController
import org.example.presentation.controller.McpController
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.example.MainKt")

fun main() {
    logger.info("Запуск сервера на порту ${AppConfig.serverPort}")
    embeddedServer(Netty, port = AppConfig.serverPort, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        })
    }
    
    install(CallLogging)
    
    // Инициализация зависимостей
    val sessionRepository = SessionRepositoryImpl()
    val perplexityRepository = PerplexityRepositoryImpl(
        apiUrl = AppConfig.perplexityApiUrl,
        apiKey = AppConfig.perplexityApiKey
    )
    val gigaChatRepository = GigaChatRepositoryImpl(
        apiUrl = AppConfig.gigachatApiUrl,
        authorizationKey = AppConfig.gigachatApiKey
    )
    val huggingFaceRepository = HuggingFaceRepositoryImpl(
        apiUrl = AppConfig.huggingFaceApiUrl,
        apiKey = AppConfig.huggingFaceApiKey ?: throw IllegalStateException(
            "HUGGINGFACE_API_KEY не установлен. " +
            "Установите переменную окружения: export HUGGINGFACE_API_KEY=your_api_key"
        )
    )
    
    val compressDialogHistoryUseCase = CompressDialogHistoryUseCase(
        perplexityRepository = perplexityRepository,
        gigaChatRepository = gigaChatRepository,
        huggingFaceRepository = huggingFaceRepository
    )
    
    val sendChatMessageUseCase = SendChatMessageUseCase(
        sessionRepository = sessionRepository,
        perplexityRepository = perplexityRepository,
        gigaChatRepository = gigaChatRepository,
        huggingFaceRepository = huggingFaceRepository,
        compressDialogHistoryUseCase = compressDialogHistoryUseCase,
        defaultModel = AppConfig.model,
        defaultMaxTokens = AppConfig.maxTokens
    )
    
    val sendMultiChatMessageUseCase = SendMultiChatMessageUseCase(
        perplexityRepository = perplexityRepository,
        gigaChatRepository = gigaChatRepository,
        huggingFaceRepository = huggingFaceRepository,
        defaultModel = AppConfig.model,
        defaultMaxTokens = AppConfig.maxTokens
    )
    
    val chatController = ChatController(
        sendChatMessageUseCase = sendChatMessageUseCase,
        sendMultiChatMessageUseCase = sendMultiChatMessageUseCase
    )
    
    val mcpController = McpController()
    
    // Настройка маршрутов
    routing {
        chatController.configureRoutes(this)
        mcpController.configureRoutes(this)
    }
    
    // Очистка ресурсов при остановке
    environment.monitor.subscribe(ApplicationStopped) {
        logger.info("Остановка сервера, очистка ресурсов")
        perplexityRepository.close()
        gigaChatRepository.close()
        huggingFaceRepository.close()
        sessionRepository.shutdown()
    }
}


