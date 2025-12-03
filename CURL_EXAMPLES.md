# Примеры curl-запросов для 3-раундового диалога

## Раунд 1: Создание новой сессии

```bash
curl -X POST http://localhost:8080/api/perplexity/chat \
  -H "Content-Type: application/json" \
  -d '{
    "systemPrompt": "Ты - эксперт в области спорта и тренажерного зала",
    "message": "Помоги мне выяснить какие веса мне подобрать для определенных упражнений и какое количество повторений",
    "model": "sonar",
    "maxTokens": 256,
    "disableSearch": true,
    "maxRounds": 3
  }'
```

**Ожидаемый ответ:**
```json
{
  "content": "Какие упражнения вас интересуют?",
  "model": "sonar",
  "isComplete": false,
  "round": 1,
  "maxRounds": 3,
  "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Важно:** Сохраните `sessionId` из ответа для следующих запросов!

---

## Раунд 2: Продолжение диалога

Замените `YOUR_SESSION_ID` на `sessionId` из первого ответа:

```bash
curl -X POST http://localhost:8080/api/perplexity/chat \
  -H "Content-Type: application/json" \
  -d '{
    "systemPrompt": "Ты - эксперт в области спорта и тренажерного зала",
    "message": "Упражнения на сегодня - хаммер, бабочка",
    "model": "sonar",
    "maxTokens": 256,
    "disableSearch": true,
    "maxRounds": 3,
    "sessionId": "YOUR_SESSION_ID"
  }'
```

**Ожидаемый ответ:**
```json
{
  "content": "Спасибо за информацию! Уточните пожалуйста...",
  "model": "sonar",
  "isComplete": false,
  "round": 2,
  "maxRounds": 3,
  "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## Раунд 3: Последний раунд (финальный ответ)

Используйте тот же `sessionId`:

```bash
curl -X POST http://localhost:8080/api/perplexity/chat \
  -H "Content-Type: application/json" \
  -d '{
    "systemPrompt": "Ты - эксперт в области спорта и тренажерного зала",
    "message": "Новичок, в зале я 2 месяца",
    "model": "sonar",
    "maxTokens": 256,
    "disableSearch": true,
    "maxRounds": 3,
    "sessionId": "YOUR_SESSION_ID"
  }'
```

**Ожидаемый ответ:**
```json
{
  "content": "Для силовой тренировки с вашими упражнениями (хаммер, бабочка, обратная бабочка, блочный трицепс) как новичку рекомендуют работать с весами, при которых можно выполнить **3–6 повторений за подход** с нагрузкой примерно **80–90% от вашего максимума на одно повторение (1ПМ)**... [полный развернутый ответ на первоначальный запрос]",
  "model": "sonar",
  "isComplete": true,
  "round": 3,
  "maxRounds": 3,
  "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Важно:** В последнем раунде `isComplete` будет `true`, и ответ должен быть полным и исчерпывающим, отвечающим на первоначальный запрос пользователя.

---

## Обработка ошибок

### Попытка продолжить завершенный диалог:
```bash
curl -X POST http://localhost:8080/api/perplexity/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Еще один вопрос",
    "maxRounds": 3,
    "sessionId": "YOUR_SESSION_ID"
  }'
```

**Ответ:**
```json
{
  "error": "Диалог завершен",
  "code": "DIALOG_COMPLETED"
}
```

### Неверный sessionId:
```bash
curl -X POST http://localhost:8080/api/perplexity/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Вопрос",
    "maxRounds": 3,
    "sessionId": "несуществующий-id"
  }'
```

**Ответ:**
```json
{
  "error": "Сессия не найдена",
  "code": "SESSION_NOT_FOUND"
}
```

---

## Примечания

1. **Порт по умолчанию:** 8080 (можно изменить через переменную окружения `SERVER_PORT`)
2. **Обратная совместимость:** Если `maxRounds` не указан, работает режим одного раунда (как раньше)
3. **TTL сессий:** Неактивные сессии автоматически удаляются через 1 час
4. **Форматирование:** Для красивого вывода JSON используйте `jq`: `curl ... | jq '.'`

