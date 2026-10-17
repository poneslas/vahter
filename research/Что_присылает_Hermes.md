# Что Hermes присылает каналу: каталог по исходникам

17.10.2026 · проверено по `upstream/hermes-agent`, HEAD 83597d9b71. Метки: **C** — видно в коде, **I** — вывод из кода, **NF** — не найдено. Это исследование; принятые решения — в `Архитектура.md`, раздел 9.

## 1. Методы адаптера, которые вызывает шлюз

| Метод | Когда | Если адаптер не переопределил |
|---|---|---|
| `send(chat_id, content, reply_to, metadata)` | всё текстовое: финал, поток, промежуточные, прогресс, уведомления, cron | обязателен |
| `edit_message(…, finalize)` | правки потока, пузырь прогресса, heartbeat | `success=False`; **пузыри прогресса молча пропадают** (C) |
| `delete_message` | эфемерные ответы по TTL, `cleanup_progress` | ничего не удаляется |
| `send_typing` | каждые 2 с, пока идёт ход; пауза на подтверждения | ничего |
| `set_status_text` + `supports_status_text` | живая фраза «is running pytest…» (по-английски) | фраз нет |
| `send_multiple_images(images=[(url, alt)])` | картинки из финала, kanban | по одной через `send_image_file` / `send_image` / `send_animation` |
| `send_image` (URL) | из `send_multiple_images` | URL уходит текстом |
| `send_image_file`, `send_document`, `send_video`, `send_voice` | вложения финала, cron, инструмент `send_message` | «⚠️ Couldn't deliver the … attachment.» |
| `_send_exec_approval_prompt` | опасная команда на сервере | текст с `/approve`, `/deny` |
| `send_clarify(question, choices, clarify_id, …)` | инструмент `clarify` | «❓ …» + нумерованный список |
| `send_slash_confirm` | `/new`, `/reset`, `/undo` с подтверждением, смена модели с предупреждением о цене | текст с `/approve`, `/cancel` |
| `send_private_notice` | системные уведомления при `notice_delivery: private` | `send()` |
| `add_reaction` / `remove_reaction` | инструмент `send_message action=react` | «does not support reactions» |
| `on_processing_start` / `on_processing_complete(outcome)` | начало и конец хода; итог SUCCESS / FAILURE / CANCELLED | ничего |
| `send_native_task_card_progress` | **только Slack** | — |

## 2. Порядок в одном ходе

1. `on_processing_start`, затем `send_typing` каждые 2 с.
2. Параллельно: пузырь прогресса, **промежуточные реплики** (`_interim_send`), подтверждения и уточнения.
3. После ответа модели:
   1. при голосовом вводе и `auto_tts` — **сначала аудио**;
   2. **финальный текст** (`notify=True`); при потоке — это правки первого сообщения, а не новое;
   3. **картинки по URL** — одним `send_multiple_images`;
   4. **картинки-файлы** — одним `send_multiple_images`;
   5. **остальные файлы по одному**: голос, видео, документы.
4. `on_processing_complete`.
5. **После него** может прийти «💾 Memory updated».

**После файлов текста в этом ходе больше нет.** Финальный текст — один на ход, и он идёт до вложений.

## 3. Что приходит обычным `send()`

Надёжно помечены только: `_interim_send` (промежуточное, подтверждение, heartbeat, memory), `notify` (финал), `expect_edits` (начало потока), `is_approval_prompt`, `job_id` (cron). Остальное — **текст без маркера**.

| Вид | Пример | Как выключить |
|---|---|---|
| Финал | текст модели, Markdown | — |
| Поток | `send` → `edit_message`… → `finalize=True`, курсор « ▉» | глобально `streaming.enabled` |
| Промежуточная реплика | текст модели между инструментами | `display.platforms.android.interim_assistant_messages` |
| Пузырь прогресса | «🔍 Searching the web for "…"», «💻 Running …», «(×3)» | `display.platforms.android.tool_progress: off` |
| Reasoning | «💭 **Reasoning:**» + блок, **приклеен к финалу** | `show_reasoning: false` (по умолчанию) |
| Подвал | «model · 68% · ~/cwd» | `runtime_footer.enabled: false` (по умолчанию) |
| Подтверждения занятости | «⚡ Interrupting current task…», «⏳ Queued…», «⏩ Steered…» | `display.busy_ack_enabled: false` (глобально) |
| Heartbeat долгой работы | «⏳ Working — 3 min — …» раз в 180 с | `long_running_notifications: false` |
| Сжатие контекста | «🗜️ … summarizing…» (часть отфильтрована) | частично |
| Ошибки провайдера | «⏱️ The AI model service is rate-limiting…», «⚠️ Sign-in … failed…» — **приходят как финал** | нет |
| Сбой хода | «Sorry, I encountered an error (X)…» | нет |
| Авто-сброс | «🔄 Session auto-reset — …» в конце финала | нет |
| Таймаут подтверждения | «⌛ Approval timed out after 5 minutes…» | — |
| Сбой субагента | «⚠️ Subagent failed — …» | `suppress_warning_notifications` |
| Фоновые процессы | «✅ Background task finished — `cmd`» — **вне хода** | `background_process_notifications: off` |
| Memory | «💾 Memory updated» — после хода | `memory_notifications: off` (только глобально) |
| Cron | «Cronjob Response: {имя}…» + `job_id` | `cron.wrap_response: false` |
| Kanban | «✔ … done — …» — вне хода | — |
| Ответы команд | «✨ New session started!», «⚡ Stopped…» — эфемерные, удаляются по TTL | — |
| Код сопряжения | «Your pairing code: `XXXX`…» | — |

**Все системные тексты — по-английски.** `display.language` переводит лишь часть.

## 4. Длинное и сложное содержимое

- **Длина.** Финал без потока **не режется**. Режется только поток — по `MAX_MESSAGE_LENGTH`, и тогда продолжение приходит новым сообщением. Cron режется до 4000 символов, если адаптер не объявил `splits_long_messages=True`.
- **Разметка.** Приходит **сырой Markdown**: код, `**жирный**`, таблицы, `![alt](url)`. Шлюз ничего не конвертирует. Незакрытые ``` в потоке он закрывает сам.
- **`platform_hint` обязателен:** без него модель не знает, что может прислать файл тегом `MEDIA:` — эта фраза есть только во встроенных подсказках платформ.
- **Файлы.**
  - Извлекаются из финала: теги `MEDIA:/путь`, картинки `![](http…)`, голые абсолютные пути (только без потока).
  - Теги и пути вырезаются из текста. Внутри кода и цитат ничего не трогается.
  - Подписей к файлам **нет** (caption пустой).
  - **Лимита на количество нет:** 40 картинок — один вызов с 40 элементами. Альбомы по 10 — только у Telegram. Лимита на размер нет.
  - **В режиме потока** `![](url)` **остаётся в тексте** и не извлекается — клиент должен рисовать картинки из Markdown сам.
- **Голос.** При `voice.auto_tts` или `/voice on` аудио приходит **до** текста через `send_voice`.
- **Отправка в другие каналы.** Инструмент `send_message` (`target: "telegram"`, файлы — тегами `MEDIA:` в тексте). В Telegram файлы уходят по одному. Агент из нашего канала технически может отправить файлы в Telegram, если там задан домашний чат.

## 5. Данные шагов для интерфейса

Хуки `pre_tool_call` / `post_tool_call`: имя, аргументы, `session_id`, `tool_call_id`, `duration_ms`, `status`, сырой результат. Платформу и чат даёт `get_session_env` (I).

| Инструмент | Что есть в результате |
|---|---|
| `web_search` | `data.web[{title, url, description}]` — источники есть |
| `web_extract` | `results[{url, title, content}]` |
| `terminal` | `output` (до 50 000 символов), `exit_code` |
| `execute_code` | `status`, `output`, `duration_seconds` |
| `read_file` / `write_file` / `patch` | содержимое / байты / **`diff`** |
| `browser_*` | `url`, `title`, снимок, путь к скриншоту |
| `image_generate` | `image` (URL или путь); `MEDIA:` дописывается сам |
| `text_to_speech` | `file_path`, `media_tag` |
| `delegate_task` | итоги субагентов |
| `todo_list` | `todos[{id, content, status, parent}]`, `revision` |
| `send_message` | `platform`, `chat_id`, `message_id` |
| `clarify` | вопрос, варианты, ответ |

Готовые подписи: `agent/display.py` — `get_tool_emoji`, `get_tool_verb` (23 глагола), `build_tool_preview`. **Только английский.**

## 6. Подтверждения и уточнения

- **Подтверждение команды:** варианты once / session / always / deny, но набор бывает урезан (smart-deny оставляет once/deny). Таймаут 5 минут. Ответ — `resolve_gateway_approval`; текстом «yes/no/always» тоже работает.
- **Уточнение:** варианты, **мультивыбор** (ответ «1, 3»), без вариантов — открытый вопрос. Кнопку «Другое» адаптер рисует сам и вызывает `mark_awaiting_text`. Вопросы приходят по одному. Протухшая карточка — «⏳ This prompt expired…».
- **Подтверждение команды чата** (`send_slash_confirm`): once / always / cancel.

## 7. Чего нет (что легко додумать)

- Структурированных событий шагов адаптер не получает — только текстовый пузырь. Карточки шагов — только через свои хуки.
- Карточки задач — только Slack.
- Помечены не все виды сообщений, а только пять.
- Финал при потоке — правки, а не отдельное сообщение.
- Шлюз не конвертирует Markdown и не режет финал.
- Reasoning — не отдельный поток.
- Подписей к файлам нет.
- После конца хода могут прийти memory, cron, фоновые процессы, kanban.
- Текст после файлов в том же ходе не приходит.
- Тексты Hermes — английские.
