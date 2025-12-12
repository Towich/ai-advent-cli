package org.example.data.service

import kotlinx.serialization.json.Json
import org.example.domain.model.DialogSession
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Сервис для сохранения и загрузки сессий диалога в JSON-файл
 */
class MemoryStorageService(
    private val storageFilePath: String = "session_memory.json"
) {
    private val logger = LoggerFactory.getLogger(MemoryStorageService::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }
    
    /**
     * Сохраняет сессию в JSON-файл
     */
    fun saveSession(session: DialogSession?): Result<Unit> {
        return try {
            val file = File(storageFilePath)
            if (session == null) {
                // Если сессия null, удаляем файл
                if (file.exists()) {
                    file.delete()
                    logger.debug("Файл сессии удален: $storageFilePath")
                }
                Result.success(Unit)
            } else {
                // Сохраняем сессию в файл
                val jsonString = json.encodeToString(DialogSession.serializer(), session)
                file.writeText(jsonString)
                logger.debug("Сессия сохранена в файл: $storageFilePath")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            logger.error("Ошибка при сохранении сессии в файл: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Загружает сессию из JSON-файла
     */
    fun loadSession(): Result<DialogSession?> {
        return try {
            val file = File(storageFilePath)
            if (!file.exists()) {
                logger.debug("Файл сессии не найден: $storageFilePath")
                return Result.success(null)
            }
            
            val jsonString = file.readText()
            if (jsonString.isBlank()) {
                logger.debug("Файл сессии пуст: $storageFilePath")
                return Result.success(null)
            }
            
            val session = json.decodeFromString(DialogSession.serializer(), jsonString)
            // Преобразуем List в MutableList для совместимости
            val sessionWithMutableList = session.copy(
                messages = session.messages.toMutableList()
            )
            logger.info("Сессия загружена из файла: $storageFilePath")
            Result.success(sessionWithMutableList)
        } catch (e: Exception) {
            logger.error("Ошибка при загрузке сессии из файла: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Проверяет, существует ли сохраненная сессия
     */
    fun hasStoredSession(): Boolean {
        val file = File(storageFilePath)
        return file.exists() && file.readText().isNotBlank()
    }
}
