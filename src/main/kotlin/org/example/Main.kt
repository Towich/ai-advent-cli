package org.example

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Job
import org.example.application.ChatWithToolsService
import kotlinx.serialization.json.Json
import org.example.data.repository.GigaChatRepositoryImpl
import org.example.data.repository.HuggingFaceRepositoryImpl
import org.example.data.repository.LocalRepositoryImpl
import org.example.data.repository.PerplexityRepositoryImpl
import org.example.data.repository.SessionRepositoryImpl
import org.example.domain.usecase.CompressDialogHistoryUseCase
import org.example.domain.usecase.SendChatMessageUseCase
import org.example.domain.usecase.SendMultiChatMessageUseCase
import org.example.domain.usecase.SendChatMessageWithToolsUseCase
import org.example.infrastructure.config.AppConfig
import org.example.infrastructure.notification.TelegramNotifier
import org.example.infrastructure.scheduler.ReminderSummaryScheduler
import org.example.infrastructure.telegram.TelegramBotService
import org.example.infrastructure.telegram.VoiceTranscriptionService
import org.example.presentation.controller.ChatController
import org.example.presentation.controller.McpController
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    val localRepository = LocalRepositoryImpl(
        apiUrl = AppConfig.localApiUrl
    )
    
    val compressDialogHistoryUseCase = CompressDialogHistoryUseCase(
        perplexityRepository = perplexityRepository,
        gigaChatRepository = gigaChatRepository,
        huggingFaceRepository = huggingFaceRepository,
        localRepository = localRepository
    )
    
    val sendChatMessageUseCase = SendChatMessageUseCase(
        sessionRepository = sessionRepository,
        perplexityRepository = perplexityRepository,
        gigaChatRepository = gigaChatRepository,
        huggingFaceRepository = huggingFaceRepository,
        localRepository = localRepository,
        compressDialogHistoryUseCase = compressDialogHistoryUseCase,
        defaultModel = AppConfig.model,
        defaultMaxTokens = AppConfig.maxTokens
    )
    
    val sendMultiChatMessageUseCase = SendMultiChatMessageUseCase(
        perplexityRepository = perplexityRepository,
        gigaChatRepository = gigaChatRepository,
        huggingFaceRepository = huggingFaceRepository,
        localRepository = localRepository,
        defaultModel = AppConfig.model,
        defaultMaxTokens = AppConfig.maxTokens
    )
    
    val sendChatMessageWithToolsUseCase = SendChatMessageWithToolsUseCase(
        perplexityRepository = perplexityRepository,
        gigaChatRepository = gigaChatRepository,
        huggingFaceRepository = huggingFaceRepository,
        localRepository = localRepository,
        defaultModel = AppConfig.model,
        defaultMaxTokens = AppConfig.maxTokens
    )

    val chatWithToolsService = ChatWithToolsService(sendChatMessageWithToolsUseCase)

    val telegramNotifier = run {
        val token = AppConfig.telegramBotToken
        val chatId = AppConfig.telegramChatId
        if (!token.isNullOrBlank() && !chatId.isNullOrBlank()) {
            logger.info("TelegramNotifier инициализирован: token=${token.take(10)}..., chatId=$chatId")
            TelegramNotifier(botToken = token, chatId = chatId)
        } else {
            logger.warn("TelegramNotifier не инициализирован: token=${if (token.isNullOrBlank()) "не установлен" else "установлен"}, chatId=${if (chatId.isNullOrBlank()) "не установлен" else "установлен"}")
            null
        }
    }

    val reminderSummaryScheduler = ReminderSummaryScheduler(
        chatWithToolsService = chatWithToolsService,
        telegramNotifier = telegramNotifier
    )
    val reminderJob: Job = reminderSummaryScheduler.start(this)
    
    // Инициализация сервиса транскрипции голоса
    val voiceTranscriptionService = try {
        logger.info("Инициализация сервиса транскрипции голоса...")
        VoiceTranscriptionService(
            whisperCommand = System.getenv("WHISPER_COMMAND") ?: "whisper",
            whisperModel = System.getenv("WHISPER_MODEL") ?: "base",
            whisperLanguage = System.getenv("WHISPER_LANGUAGE")?.takeIf { it.isNotBlank() }
        )
    } catch (e: Exception) {
        logger.warn("Не удалось инициализировать сервис транскрипции: ${e.message}. Голосовые сообщения не будут обрабатываться.")
        null
    }
    
    // Инициализация Telegram-бота
    val telegramBotService = run {
        if (AppConfig.telegramBotEnabled) {
            val token = AppConfig.telegramBotToken
            if (token.isNullOrBlank()) {
                logger.warn("TELEGRAM_BOT_ENABLED=true, но TELEGRAM_BOT_TOKEN не установлен. Telegram-бот не будет запущен.")
                null
            } else {
                logger.info("Инициализация Telegram-бота...")
                TelegramBotService(
                    botToken = token,
                    chatWithToolsService = chatWithToolsService,
                    defaultVendor = AppConfig.telegramBotDefaultVendor,
                    defaultModel = AppConfig.telegramBotDefaultModel,
                    defaultMaxTokens = AppConfig.maxTokens,
                    defaultMcpServerUrls = AppConfig.mcpServerUrls,
                    defaultMaxToolIterations = AppConfig.telegramBotDefaultMaxToolIterations,
                    voiceTranscriptionService = voiceTranscriptionService
                )
            }
        } else {
            null
        }
    }
    
    // Запуск Telegram-бота в отдельной корутине
    val telegramBotScope = CoroutineScope(SupervisorJob())
    telegramBotService?.let { bot ->
        logger.info("Запуск Telegram-бота в режиме long polling")
        bot.startLongPolling(telegramBotScope)
    }
    
    val chatController = ChatController(
        sendChatMessageUseCase = sendChatMessageUseCase,
        sendMultiChatMessageUseCase = sendMultiChatMessageUseCase,
        sendChatMessageWithToolsUseCase = sendChatMessageWithToolsUseCase,
        telegramNotifier = telegramNotifier
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
        reminderJob.cancel()
        telegramNotifier?.close()
        telegramBotService?.close()
        telegramBotScope.cancel()
        perplexityRepository.close()
        gigaChatRepository.close()
        huggingFaceRepository.close()
        localRepository.close()
        sessionRepository.shutdown()
    }
}


