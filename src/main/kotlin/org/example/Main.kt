package org.example

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.example.data.repository.PerplexityRepositoryImpl
import org.example.data.repository.SessionRepositoryImpl
import org.example.domain.usecase.SendChatMessageUseCase
import org.example.infrastructure.config.AppConfig
import org.example.presentation.controller.ChatController

fun main() {
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
        apiUrl = AppConfig.apiUrl,
        apiKey = AppConfig.apiKey
    )
    
    val sendChatMessageUseCase = SendChatMessageUseCase(
        sessionRepository = sessionRepository,
        perplexityRepository = perplexityRepository,
        defaultModel = AppConfig.model,
        defaultMaxTokens = AppConfig.maxTokens
    )
    
    val chatController = ChatController(sendChatMessageUseCase)
    
    // Настройка маршрутов
    routing {
        chatController.configureRoutes(this)
    }
    
    // Очистка ресурсов при остановке
    environment.monitor.subscribe(ApplicationStopped) {
        perplexityRepository.close()
        sessionRepository.shutdown()
    }
}


