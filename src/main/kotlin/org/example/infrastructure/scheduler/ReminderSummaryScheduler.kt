package org.example.infrastructure.scheduler

import kotlinx.coroutines.*
import org.example.application.ChatWithToolsService
import org.example.infrastructure.config.AppConfig
import org.example.infrastructure.notification.TelegramNotifier
import org.slf4j.LoggerFactory

class ReminderSummaryScheduler(
    private val chatWithToolsService: ChatWithToolsService,
    private val telegramNotifier: TelegramNotifier?
) {
    private val logger = LoggerFactory.getLogger(ReminderSummaryScheduler::class.java)

    fun start(scope: CoroutineScope): Job {
        val enabled = AppConfig.reminderSummaryEnabled
        if (!enabled) {
            logger.info("ReminderSummaryScheduler выключен (REMINDER_SUMMARY_ENABLED=false)")
            return SupervisorJob().apply { cancel() }
        }

        val intervalMs = AppConfig.reminderSummaryIntervalMs
        require(intervalMs >= 5_000L) { "REMINDER_SUMMARY_INTERVAL_MS слишком маленький (min 5000ms)" }

        logger.info("ReminderSummaryScheduler включен: intervalMs=$intervalMs, mcpServerUrl=${AppConfig.mcpServerUrl}")

        return scope.launch(Dispatchers.Default + SupervisorJob()) {
            while (isActive) {
                val startedAt = System.currentTimeMillis()
                try {
                    val summary = buildSummaryOnce()
                    if (summary.isNotBlank()) {
                        if (telegramNotifier != null) {
                            telegramNotifier.sendText(summary).onFailure { e ->
                                logger.error("Не удалось отправить summary в Telegram: ${e.message}", e)
                            }
                        } else {
                            logger.info("Telegram не настроен, summary:\n$summary")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Ошибка в ReminderSummaryScheduler: ${e.message}", e)
                } finally {
                    val spent = System.currentTimeMillis() - startedAt
                    val delayMs = (intervalMs - spent).coerceAtLeast(0L)
                    delay(delayMs)
                }
            }
        }
    }

    private suspend fun buildSummaryOnce(): String {
        val vendor = AppConfig.reminderSummaryVendor ?: "perplexity"

        val message = """
            Ты — мой персональный ассистент-органайзер.
            Твоя задача: получить список задач/напоминаний через MCP tool `reminder` и выдать короткую сводку.

            Обязательно:
            - Сначала используй tool `reminder` (например list/get/active — выбери подходящий).
            - Затем верни финальный ответ как краткое summary: что срочно, что просрочено, что на сегодня/на неделю.
            - Формат: 5–12 буллетов, без лишней воды.
        """.trimIndent()

        val result = chatWithToolsService.execute(
            ChatWithToolsService.Command(
                message = message,
                vendor = vendor,
                model = AppConfig.reminderSummaryModel,
                maxTokens = null,
                disableSearch = true,
                systemPrompt = null,
                outputFormat = null,
                outputSchema = null,
                temperature = 0.2,
                mcpServerUrl = AppConfig.mcpServerUrl,
                maxToolIterations = AppConfig.reminderSummaryMaxToolIterations
            )
        )

        return result.fold(
            onSuccess = { it.content.trim() },
            onFailure = { e ->
                logger.error("Ошибка при генерации summary: ${e.message}", e)
                ""
            }
        )
    }
}


