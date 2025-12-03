#!/bin/bash

# Примеры curl-запросов для 3-раундового диалога
# Сервер должен быть запущен на порту 8080

echo "=== Раунд 1: Создание новой сессии ==="
echo ""

RESPONSE1=$(curl -X POST http://localhost:8080/api/perplexity/chat \
  -H "Content-Type: application/json" \
  -d '{
    "systemPrompt": "Ты - эксперт в области спорта и тренажерного зала",
    "message": "Помоги мне выяснить какие веса мне подобрать для определенных упражнений и какое количество повторений",
    "model": "sonar",
    "maxTokens": 256,
    "disableSearch": true,
    "maxRounds": 3
  }')

echo "$RESPONSE1" | jq '.'
echo ""

# Извлекаем sessionId из ответа (требуется jq)
SESSION_ID=$(echo "$RESPONSE1" | jq -r '.sessionId')
echo "Session ID: $SESSION_ID"
echo ""
echo "=== Раунд 2: Продолжение диалога ==="
echo ""

RESPONSE2=$(curl -X POST http://localhost:8080/api/perplexity/chat \
  -H "Content-Type: application/json" \
  -d "{
    \"systemPrompt\": \"Ты - эксперт в области спорта и тренажерного зала\",
    \"message\": \"Упражнения на сегодня - хаммер, бабочка\",
    \"model\": \"sonar\",
    \"maxTokens\": 256,
    \"disableSearch\": true,
    \"maxRounds\": 3,
    \"sessionId\": \"$SESSION_ID\"
  }")

echo "$RESPONSE2" | jq '.'
echo ""
echo "=== Раунд 3: Последний раунд (финальный ответ) ==="
echo ""

RESPONSE3=$(curl -X POST http://localhost:8080/api/perplexity/chat \
  -H "Content-Type: application/json" \
  -d "{
    \"systemPrompt\": \"Ты - эксперт в области спорта и тренажерного зала\",
    \"message\": \"Новичок, в зале я 2 месяца\",
    \"model\": \"sonar\",
    \"maxTokens\": 256,
    \"disableSearch\": true,
    \"maxRounds\": 3,
    \"sessionId\": \"$SESSION_ID\"
  }")

echo "$RESPONSE3" | jq '.'
echo ""
echo "=== Проверка завершения диалога ==="
echo "isComplete должен быть true:"
echo "$RESPONSE3" | jq '.isComplete'

