package org.example.infrastructure.telegram

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Хранилище настроек пользователей для сохранения между перезапусками приложения
 */
class UserSettingsStorage(
    private val storageDir: String = "telegram_chat_history"
) {
    private val logger = LoggerFactory.getLogger(UserSettingsStorage::class.java)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private val settingsFilePath = "$storageDir/user_settings.json"
    
    init {
        // Создаем директорию для хранения настроек, если её нет
        val dir = File(storageDir)
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info("Создана директория для настроек: $storageDir")
        }
    }
    
    @Serializable
    data class UserSettingsData(
        val vendor: String,
        val model: String? = null,
        val maxTokens: Int? = null
    )
    
    @Serializable
    data class AllUserSettings(
        val users: Map<String, UserSettingsData> = emptyMap()
    )
    
    /**
     * Загружает все настройки пользователей из файла
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun loadAllSettings(): Map<Long, UserSettingsData> {
        return try {
            val file = File(settingsFilePath)
            if (!file.exists()) {
                logger.debug("Файл настроек не найден: $settingsFilePath")
                return emptyMap()
            }
            
            file.inputStream().use { input ->
                val allSettings = json.decodeFromStream<AllUserSettings>(input)
                logger.info("Загружены настройки для ${allSettings.users.size} пользователей")
                allSettings.users.mapKeys { it.key.toLongOrNull() ?: -1L }
                    .filterKeys { it > 0 }
            }
        } catch (e: Exception) {
            logger.error("Ошибка при загрузке настроек: ${e.message}", e)
            emptyMap()
        }
    }
    
    /**
     * Сохраняет настройки всех пользователей в файл
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun saveAllSettings(settings: Map<Long, UserSettingsData>) {
        try {
            val file = File(settingsFilePath)
            val allSettings = AllUserSettings(
                users = settings.mapKeys { it.key.toString() }
            )
            
            file.outputStream().use { output ->
                json.encodeToStream(allSettings, output)
            }
            logger.debug("Сохранены настройки для ${settings.size} пользователей")
        } catch (e: Exception) {
            logger.error("Ошибка при сохранении настроек: ${e.message}", e)
        }
    }
    
    /**
     * Загружает настройки конкретного пользователя
     */
    fun loadUserSettings(chatId: Long): UserSettingsData? {
        return loadAllSettings()[chatId]
    }
    
    /**
     * Сохраняет настройки конкретного пользователя
     */
    fun saveUserSettings(chatId: Long, settings: UserSettingsData) {
        val allSettings = loadAllSettings().toMutableMap()
        allSettings[chatId] = settings
        saveAllSettings(allSettings)
    }
}

