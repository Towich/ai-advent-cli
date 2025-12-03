# Техническое задание: Реализация ведения диалога с нейронной сетью

## 1. Общее описание

Необходимо реализовать функциональность ведения многораундового диалога с нейронной сетью Perplexity. Каждый диалог должен иметь ограничение по количеству раундов (maxRounds), и система должна отслеживать текущий раунд и завершение диалога.

## 2. Требования к API

### 2.1. Входящий запрос (ChatApiRequest)

**Текущая структура:**
```kotlin
data class ChatApiRequest(
    val message: String,
    val model: String? = null,
    val maxTokens: Int? = null,
    val disableSearch: Boolean? = null,
    val systemPrompt: String? = null,
    val outputFormat: String? = null,
    val outputSchema: String? = null
)
```

**Изменения:**
- Добавить поле `maxRounds: Int? = null` - максимальное количество раундов в диалоге
- Добавить поле `sessionId: String? = null` - идентификатор сессии диалога (опционально, для продолжения существующего диалога)

**Новая структура:**
```kotlin
data class ChatApiRequest(
    val message: String,
    val model: String? = null,
    val maxTokens: Int? = null,
    val disableSearch: Boolean? = null,
    val systemPrompt: String? = null,
    val outputFormat: String? = null,
    val outputSchema: String? = null,
    val maxRounds: Int? = null,        // НОВОЕ
    val sessionId: String? = null      // НОВОЕ
)
```

### 2.2. Исходящий ответ (ChatApiResponse)

**Текущая структура:**
```kotlin
data class ChatApiResponse(
    val response: String,
    val model: String? = null
)
```

**Изменения:**
- Переименовать поле `response` в `content` для соответствия примеру
- Добавить поле `isComplete: Boolean` - флаг завершения диалога
- Добавить поле `round: Int` - текущий номер раунда
- Добавить поле `maxRounds: Int` - максимальное количество раундов
- Добавить поле `sessionId: String` - идентификатор сессии (для последующих запросов)

**Новая структура:**
```kotlin
data class ChatApiResponse(
    val content: String,           // переименовано из response
    val model: String? = null,
    val isComplete: Boolean,       // НОВОЕ
    val round: Int,                // НОВОЕ
    val maxRounds: Int,            // НОВОЕ
    val sessionId: String          // НОВОЕ
)
```

## 3. Логика работы диалога

### 3.1. Создание новой сессии

**Первый запрос (без sessionId):**
- Если указан `maxRounds`, создается новая сессия диалога
- Генерируется уникальный `sessionId` (UUID)
- Инициализируется история сообщений с системным промптом (если указан) и первым пользовательским сообщением
- Текущий раунд = 1
- `isComplete = false` (если round < maxRounds) или `isComplete = true` (если round >= maxRounds)

### 3.2. Продолжение существующей сессии

**Последующие запросы (с sessionId):**
- Поиск существующей сессии по `sessionId`
- Если сессия не найдена - возврат ошибки `SESSION_NOT_FOUND`
- Проверка, не завершен ли диалог (`isComplete == true`)
  - Если завершен - возврат ошибки `DIALOG_COMPLETED`
- Проверка, не превышен ли лимит раундов
  - Если `round >= maxRounds` - завершить диалог (`isComplete = true`)
- Добавление нового пользовательского сообщения в историю
- Увеличение счетчика раунда на 1
- Отправка полной истории сообщений в Perplexity API

### 3.3. Завершение диалога

Диалог считается завершенным (`isComplete = true`), если:
- Текущий раунд достиг или превысил `maxRounds`
- После получения ответа от API устанавливается `isComplete = true` и `round = maxRounds`

### 3.4. Логика последнего раунда

**Важное требование:** В последнем раунде (когда `currentRound == maxRounds`) Perplexity должна:
1. Понимать, что это финальный раунд диалога
2. Собрать всю информацию, полученную в предыдущих раундах
3. Дать полный и исчерпывающий ответ на первоначальный запрос пользователя

**Реализация:**
- При формировании системного промпта для последнего раунда добавлять специальную инструкцию
- Инструкция должна явно указывать, что это последний раунд и нужно дать финальный ответ
- В системном промпте должен быть доступен первоначальный запрос пользователя (из первого раунда)

## 4. Хранение сессий

### 4.1. Структура данных сессии

```kotlin
data class DialogSession(
    val sessionId: String,
    val systemPrompt: String?,
    val messages: MutableList<Message>,  // История сообщений (system + user + assistant)
    var currentRound: Int,               // var для возможности изменения
    val maxRounds: Int,
    val model: String,
    val maxTokens: Int,
    val disableSearch: Boolean,
    val createdAt: Long,
    var lastActivityAt: Long,            // var для обновления времени активности
    val initialUserMessage: String,      // НОВОЕ: сохраняем первоначальный запрос
    var isComplete: Boolean = false      // НОВОЕ: флаг завершения диалога
)
```

### 4.2. Механизм хранения

**Вариант 1 (рекомендуемый для MVP):** In-memory хранилище
- Использовать `ConcurrentHashMap<String, DialogSession>` для хранения сессий
- Ключ: `sessionId`
- Очистка старых сессий по TTL (например, 1 час без активности)

### 4.3. Управление жизненным циклом сессий

- При создании сессии устанавливать `createdAt` и `lastActivityAt`
- При каждом обращении обновлять `lastActivityAt`
- Реализовать фоновую задачу для очистки неактивных сессий (TTL = 1 час)

## 5. Изменения в PerplexityService

### 5.1. Метод sendMessage

**Текущая сигнатура:**
```kotlin
suspend fun sendMessage(request: ChatApiRequest): Result<Pair<String, String>>
```

**Новая сигнатура:**
```kotlin
suspend fun sendMessage(
    request: ChatApiRequest,
    session: DialogSession?,
    isLastRound: Boolean = false
): Result<Pair<String, String>>
```

**Изменения в логике:**
- Если `session != null`, использовать историю сообщений из сессии
- Добавлять новое пользовательское сообщение в историю
- **Если это последний раунд** (`session.currentRound == session.maxRounds`):
  - Модифицировать системный промпт, добавив инструкцию о финальном ответе
  - Включить в системный промпт напоминание о первоначальном запросе пользователя
- Отправлять всю историю (включая системное сообщение и все предыдущие раунды) в Perplexity API
- Возвращать только контент последнего ответа ассистента

### 5.2. Новый метод для работы с историей

```kotlin
private fun buildMessagesHistory(
    systemPrompt: String?,
    userMessages: List<String>,
    assistantMessages: List<String>
): List<Message>
```

Метод формирует список сообщений в формате:
1. System message (если есть)
2. User message 1
3. Assistant message 1
4. User message 2
5. Assistant message 2
6. ... и т.д.

### 5.3. Метод для формирования системного промпта с учетом раунда

```kotlin
private fun buildSystemPromptWithRoundContext(
    baseSystemPrompt: String?,
    isLastRound: Boolean,
    initialUserMessage: String?,
    currentRound: Int,
    maxRounds: Int
): String?
```

**Логика работы:**
- Если `isLastRound == true`:
  - Добавить в системный промпт специальную инструкцию:
    ```
    ВАЖНО: Это последний раунд диалога (раунд $currentRound из $maxRounds).
    Первоначальный запрос пользователя был: "$initialUserMessage"
    
    Твоя задача:
    1. Собрать всю информацию, полученную в предыдущих раундах диалога
    2. Проанализировать все ответы пользователя на твои вопросы
    3. Дать полный, исчерпывающий и структурированный ответ на первоначальный запрос пользователя
    4. Не задавай новых вопросов - это финальный ответ
    
    Ответ должен быть максимально полным и полезным, учитывая всю собранную информацию.
    ```
- Если `isLastRound == false`:
  - Использовать базовый системный промпт без изменений
  - Можно добавить информацию о текущем раунде: "Текущий раунд: $currentRound из $maxRounds"

**Пример формирования системного промпта для последнего раунда:**

Базовый промпт:
```
Ты - эксперт в области спорта и тренажерного зала
```

Модифицированный промпт для последнего раунда:
```
Ты - эксперт в области спорта и тренажерного зала

ВАЖНО: Это последний раунд диалога (раунд 3 из 3).
Первоначальный запрос пользователя был: "Помоги мне выяснить какие веса мне подобрать для определенных упражнений и какое количество повторений"

Твоя задача:
1. Собрать всю информацию, полученную в предыдущих раундах диалога
2. Проанализировать все ответы пользователя на твои вопросы
3. Дать полный, исчерпывающий и структурированный ответ на первоначальный запрос пользователя
4. Не задавай новых вопросов - это финальный ответ

Ответ должен быть максимально полным и полезным, учитывая всю собранную информацию.
```

## 6. Изменения в Main.kt (роутинг)

### 6.1. Управление сессиями

- Создать объект `SessionManager` для управления сессиями
- Интегрировать `SessionManager` в обработчик `/api/perplexity/chat`

### 6.2. Логика обработки запроса

```kotlin
post("/api/perplexity/chat") {
    // 1. Получить запрос
    val request = call.receive<ChatApiRequest>()
    
    // 2. Валидация
    if (request.message.isBlank()) { ... }
    if (request.maxRounds != null && request.maxRounds < 1) { ... }
    
    // 3. Получить или создать сессию
    val session = if (request.sessionId != null) {
        sessionManager.getSession(request.sessionId)
            ?: return@post call.respond(..., ErrorResponse("Сессия не найдена", "SESSION_NOT_FOUND"))
    } else {
        // При создании новой сессии сохраняем первоначальное сообщение
        sessionManager.createSession(request)
    }
    
    // 4. Проверить, не завершен ли диалог
    if (session.isComplete) {
        return@post call.respond(..., ErrorResponse("Диалог завершен", "DIALOG_COMPLETED"))
    }
    
    // 5. Проверить лимит раундов
    if (session.currentRound >= session.maxRounds) {
        session.isComplete = true
        return@post call.respond(...)
    }
    
    // 6. Определить, является ли это последним раундом
    val isLastRound = (session.currentRound + 1) >= session.maxRounds
    
    // 7. Отправить сообщение (передаем информацию о последнем раунде)
    val result = perplexityService.sendMessage(request, session, isLastRound)
    
    // 7. Обновить сессию
    session.addUserMessage(request.message)
    if (result.isSuccess) {
        val (content, model) = result.getOrThrow()
        session.addAssistantMessage(content)
        session.incrementRound()
        session.updateLastActivity()
        
        val isComplete = session.currentRound >= session.maxRounds
        if (isComplete) {
            session.isComplete = true
        }
        
        // 8. Вернуть ответ
        call.respond(ChatApiResponse(
            content = content,
            model = model,
            isComplete = isComplete,
            round = session.currentRound,
            maxRounds = session.maxRounds,
            sessionId = session.sessionId
        ))
    } else {
        // Обработка ошибки
    }
}
```

## 7. Валидация

### 7.1. Валидация запроса

- `message` - обязательное поле, не может быть пустым
- `maxRounds` - если указано, должно быть >= 1
- `sessionId` - если указано, должно быть валидным UUID и сессия должна существовать

### 7.2. Обработка ошибок

**Новые коды ошибок:**
- `SESSION_NOT_FOUND` (404) - сессия с указанным `sessionId` не найдена
- `DIALOG_COMPLETED` (400) - попытка продолжить завершенный диалог
- `INVALID_MAX_ROUNDS` (400) - невалидное значение `maxRounds` (< 1)
- `MAX_ROUNDS_EXCEEDED` (400) - превышен лимит раундов

## 8. Примеры использования

### 8.1. Первый запрос (создание сессии)

**Запрос:**
```json
{
    "systemPrompt": "Ты - эксперт в области спорта и тренажерного зала",
    "message": "Помоги мне выяснить какие веса мне подобрать",
    "model": "sonar",
    "maxTokens": 256,
    "disableSearch": true,
    "maxRounds": 3
}
```

**Ответ:**
```json
{
    "content": "Какие упражнения вас интересуют?",
    "isComplete": false,
    "round": 1,
    "maxRounds": 3,
    "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 8.2. Второй запрос (продолжение диалога)

**Запрос:**
```json
{
    "systemPrompt": "Ты - эксперт в области спорта и тренажерного зала",
    "message": "Упражнения на сегодня - хаммер, бабочка",
    "model": "sonar",
    "maxTokens": 256,
    "disableSearch": true,
    "maxRounds": 3,
    "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Ответ:**
```json
{
    "content": "Спасибо за информацию! Уточните пожалуйста...",
    "isComplete": false,
    "round": 2,
    "maxRounds": 3,
    "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 8.3. Третий запрос (завершение диалога)

**Запрос:**
```json
{
    "systemPrompt": "Ты - эксперт в области спорта и тренажерного зала",
    "message": "Новичок, в зале я 2 месяца",
    "model": "sonar",
    "maxTokens": 256,
    "disableSearch": true,
    "maxRounds": 3,
    "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Ответ:**
```json
{
    "content": "Для силовой тренировки с вашими упражнениями (хаммер, бабочка, обратная бабочка, блочный трицепс) как новичку рекомендуют работать с весами, при которых можно выполнить **3–6 повторений за подход** с нагрузкой примерно **80–90% от вашего максимума на одно повторение (1ПМ)**... [полный развернутый ответ на первоначальный запрос]",
    "isComplete": true,
    "round": 3,
    "maxRounds": 3,
    "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Важно:** В последнем раунде ответ должен быть полным и исчерпывающим, отвечающим на первоначальный запрос пользователя, а не просто ответом на последний вопрос.

## 9. Обратная совместимость

- Если `maxRounds` не указан в запросе, работать в режиме одного раунда (как сейчас)
- Если `sessionId` не указан, каждый запрос обрабатывается независимо
- Поле `response` в ответе переименовано в `content` - это breaking change, но необходимо для соответствия требованиям

## 11. Этапы реализации

1. **Этап 1:** Изменение структур данных (DTO)
   - Добавить поля в `ChatApiRequest` и `ChatApiResponse`
   - Создать `DialogSession` и `SessionManager`

2. **Этап 2:** Реализация SessionManager
   - In-memory хранилище сессий
   - Методы создания, получения, обновления сессий
   - Очистка неактивных сессий

3. **Этап 3:** Изменение PerplexityService
   - Поддержка истории сообщений
   - Модификация метода `sendMessage` с параметром `isLastRound`
   - Реализация метода `buildSystemPromptWithRoundContext` для формирования системного промпта с учетом последнего раунда
   - Сохранение и использование первоначального запроса пользователя

4. **Этап 4:** Изменение роутинга
   - Интеграция SessionManager
   - Обработка логики диалога

5. **Этап 5:** Тестирование и отладка

## 12. Дополнительные улучшения

- Логирование всех запросов/ответов для отладки

