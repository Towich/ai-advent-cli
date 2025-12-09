# Примеры запросов к API

## Базовые примеры

### 1. Запрос к Perplexity API

#### Простой запрос с указанием vendor
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Привет! Расскажи о Kotlin",
    "vendor": "perplexity"
  }'
```

#### Запрос с указанием модели Perplexity
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Что такое искусственный интеллект?",
    "vendor": "perplexity",
    "model": "sonar",
    "maxTokens": 512,
    "temperature": 0.7
  }'
```

#### Запрос с системным промптом
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Создай план проекта",
    "vendor": "perplexity",
    "model": "sonar-pro",
    "systemPrompt": "Ты опытный разработчик. Отвечай кратко и по делу.",
    "maxTokens": 256,
    "disableSearch": false
  }'
```

#### Запрос с многораундовым диалогом
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Помоги мне создать веб-приложение",
    "vendor": "perplexity",
    "model": "sonar",
    "maxRounds": 3,
    "systemPrompt": "Ты помощник по разработке. Задавай уточняющие вопросы."
  }'
```

#### Запрос с JSON форматом вывода
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Создай список из 3 книг о программировании",
    "vendor": "perplexity",
    "model": "sonar",
    "outputFormat": "json",
    "outputSchema": "{\"books\": [{\"title\": \"string\", \"author\": \"string\"}]}"
  }'
```

---

### 2. Запрос к GigaChat API

#### Простой запрос с указанием vendor
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Привет! Расскажи о Kotlin",
    "vendor": "gigachat",
    "model": "GigaChat-2"
  }'
```

#### Запрос с моделью GigaChat-2-Pro
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Объясни концепцию машинного обучения",
    "vendor": "gigachat",
    "model": "GigaChat-2-Pro",
    "maxTokens": 1024,
    "temperature": 0.8
  }'
```

#### Запрос с моделью GigaChat-2-Max
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Напиши код на Kotlin для работы с HTTP клиентом",
    "vendor": "gigachat",
    "model": "GigaChat-2-Max",
    "systemPrompt": "Ты эксперт по Kotlin. Пиши чистый и понятный код.",
    "maxTokens": 512
  }'
```

#### Запрос с многораундовым диалогом
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Помоги мне выбрать фреймворк для веб-разработки",
    "vendor": "gigachat",
    "model": "GigaChat-2",
    "maxRounds": 5,
    "systemPrompt": "Ты консультант по технологиям. Задавай вопросы о требованиях проекта."
  }'
```

#### Запрос с JSON форматом вывода
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Создай структуру данных для пользователя с полями: имя, email, возраст",
    "vendor": "gigachat",
    "model": "GigaChat-2",
    "outputFormat": "json",
    "outputSchema": "{\"user\": {\"name\": \"string\", \"email\": \"string\", \"age\": \"number\"}}"
  }'
```

---

## Использование универсального эндпоинта

Эндпоинт `/api/chat` требует обязательного указания поля `vendor`:

### Указание vendor

#### Perplexity
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Привет!",
    "vendor": "perplexity",
    "model": "sonar"
  }'
```

#### GigaChat
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Привет!",
    "vendor": "gigachat",
    "model": "GigaChat-2"
  }'
```

---

## Примеры ответов

### Успешный ответ
```json
{
  "content": "Привет! Kotlin — это современный язык программирования...",
  "model": "sonar",
  "isComplete": true,
  "round": 1,
  "maxRounds": 1
}
```

### Ответ в многораундовом диалоге
```json
{
  "content": "Какие технологии тебя интересуют?",
  "model": "GigaChat-2",
  "isComplete": false,
  "round": 1,
  "maxRounds": 3
}
```

---

## Параметры запроса

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `message` | String | Да | Текст сообщения пользователя |
| `vendor` | String | Да | Вендор для использования: "perplexity" или "gigachat" |
| `model` | String | Нет | Модель для использования |
| `maxTokens` | Int | Нет | Максимальное количество токенов в ответе |
| `disableSearch` | Boolean | Нет | Отключить поиск (только для Perplexity) |
| `systemPrompt` | String | Нет | Системный промпт для настройки поведения |
| `outputFormat` | String | Нет | Формат вывода (например, "json") |
| `outputSchema` | String | Нет | JSON схема для валидации ответа |
| `maxRounds` | Int | Нет | Количество раундов диалога |
| `temperature` | Double | Нет | Температура генерации (0.0 - 2.0) |

---

## Доступные модели

### Perplexity
- `sonar` (по умолчанию)
- `sonar-pro`
- `sonar-online`
- Любая модель, содержащая "perplexity" или начинающаяся с "sonar"

### GigaChat
- `GigaChat-2` (по умолчанию)
- `GigaChat-2-Pro`
- `GigaChat-2-Max`
- Любая модель, содержащая "gigachat" или "GigaChat"

---

## Переменные окружения

Перед запуском сервера необходимо установить:

```bash
# Для Perplexity
export PERPLEXITY_API_KEY=your_perplexity_api_key
export PERPLEXITY_API_URL=https://api.perplexity.ai/chat/completions  # опционально
export PERPLEXITY_MODEL=sonar  # опционально

# Для GigaChat
export GIGACHAT_API_KEY=your_gigachat_api_key
export GIGACHAT_API_URL=https://gigachat.devices.sberbank.ru/api/v1/chat/completions  # опционально
export GIGACHAT_MODEL=GigaChat-2  # опционально

# Общие настройки
export SERVER_PORT=8080  # опционально, по умолчанию 8080
```
