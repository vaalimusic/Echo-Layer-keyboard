# Echo Layer: Technical Overview

## Что это за проект

`Echo Layer` — это Android IME на базе `LatinIME`, расширенная двумя крупными подсистемами:

1. `Invisible mode` — скрытое кодирование и декодирование сообщений прямо из клавиатуры.
2. `AI rewrite` — переписывание черновика через внешний LLM до отправки или по явному действию пользователя.

Проект живёт внутри ограничений Android `InputMethodService`, поэтому архитектура построена вокруг трёх фактов:

- клавиатура не владеет чужим текстовым полем полностью;
- она зависит от `InputConnection` и от того, что конкретное приложение разрешает прочитать и заменить;
- поведение мессенджеров, MIUI и старых Android-устройств отличается сильнее, чем кажется.

## Архитектура по слоям

### 1. IME shell

Главный orchestration-слой — [LatinIME.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java).

Он отвечает за:

- lifecycle клавиатуры;
- работу с `RichInputConnection`;
- показ candidate/T9 строки;
- запуск invisible flow;
- запуск AI rewrite flow;
- работу с clipboard decode, когда это возможно в рамках живого IME.

Это точка интеграции всех пользовательских сценариев.

### 2. Invisible domain

Основной invisible-функционал находится в пакете:

- [invisible/InvisibleMessageController.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/InvisibleMessageController.java)
- [invisible/CompositeInvisibleCodec.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/CompositeInvisibleCodec.java)
- [invisible/InvisiblePayloadEnvelope.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/InvisiblePayloadEnvelope.java)
- [invisible/InvisibleTargetResolver.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/InvisibleTargetResolver.java)
- [invisible/PassphraseStore.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/PassphraseStore.java)
- [invisible/CoverTemplateRepository.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/CoverTemplateRepository.java)

Здесь разделены три задачи:

- что именно кодировать;
- как именно кодировать;
- как хранить настройки и секреты.

### 3. Candidate/T9 presentation

Для расшифровки и подсказок используется отдельный presentation-слой:

- [CandidateStripView.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/CandidateStripView.java)
- [CandidateStripControllerImpl.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/CandidateStripControllerImpl.java)
- [CandidatePresentationCoordinator.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/CandidatePresentationCoordinator.java)

Изначально decode показывался как popup и overlay, но это оказалось нестабильно. В итоге проект пришёл к T9-подобной строке внутри клавиатуры.

### 4. AI layer

AI-переписывание отделено в свои классы:

- [ai/AiProvider.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/ai/AiProvider.java)
- [ai/AiDraftResolver.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/ai/AiDraftResolver.java)
- [ai/AiRewriteClient.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/ai/AiRewriteClient.java)

UI-настройки вынесены отдельно:

- [settings/AiSettingsFragment.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/AiSettingsFragment.java)
- [res/xml/prefs_screen_ai.xml](./app/src/main/res/xml/prefs_screen_ai.xml)

## Как работает invisible encode

### Шаг 1. Определение текста-цели

`InvisibleMessageController` не кодирует текст “вслепую”. Сначала `InvisibleTargetResolver` пытается получить корректный диапазон:

1. выделение;
2. surrounding text;
3. где возможно — `ExtractedText` для полного черновика.

Это критично, потому что `InputConnection` далеко не всегда отдаёт весь текст.

### Шаг 2. Нормализация текста

Перед шифрованием текст нормализуется:

- убираются опасные переводы строк;
- схлопываются спец-разделители;
- для чатовых сценариев сообщение приводится к single-line представлению.

Это сделано потому, что мессенджеры любят дробить draft на несколько сообщений, если в encoded carrier остаются переносы или составные форматные символы.

### Шаг 3. Выбор carrier-режима

Поддерживаются режимы:

- `INVISIBLE_UNICODE`
- `HOMOGLYPH`
- `WHITESPACE`
- `VISIBLE_TOKEN`
- `AUTO`

`AUTO` перебирает режимы и после encode делает self-check через decode. Если roundtrip не проходит, выбирается следующий режим.

### Шаг 4. Построение криптоконверта

Шифрование реализовано в [CompositeInvisibleCodec.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/CompositeInvisibleCodec.java).

Текущий pipeline такой:

1. plaintext переводится в UTF-8;
2. при выгоде по размеру данные сжимаются;
3. генерируются случайные `salt` и `nonce`;
4. ключ сообщения выводится через `PBKDF2WithHmacSHA256`;
5. полезная нагрузка шифруется через `AES/GCM/NoPadding`;
6. всё упаковывается в `InvisiblePayloadEnvelope`.

Параметры сейчас такие:

- `PBKDF2`: `120_000` итераций;
- `salt`: `16` байт;
- `nonce`: `12` байт;
- `AES-256-GCM`.

### Шаг 5. Встраивание в carrier

После получения envelope используется один из вариантов:

- invisible format chars;
- похожие глифы;
- пробелы / non-breaking spaces;
- компактный видимый token.

Для новых invisible-сообщений используются нейтральные format-символы `U+2060/U+2061/U+2062/U+2063`, а legacy `ZWJ/ZWNJ` остаются только для обратной совместимости на decode.

Это было сделано потому, что `ZWJ/ZWNJ` плохо переживали мессенджеры и могли ломать отправку.

## Как работает invisible decode

Decode идёт в обратном порядке:

1. система пытается извлечь envelope из visible token или hidden carrier;
2. envelope парсится;
3. ciphertext расшифровывается тем же PBKDF2-derived key;
4. если payload был compressed, он распаковывается;
5. результат выводится в candidate/T9 строку.

Важный UX-принцип: decode не должен портить текст в чате. Поэтому расшифровка по умолчанию показывается не заменой текста в поле, а отдельно в keyboard strip.

## Зачем нужен compact mode

Большие hidden-tail сообщения для чатов оказались опасны:

- могли дробиться на несколько сообщений;
- могли вызывать пустые хвосты;
- могли тянуть за собой title/link preview;
- могли физически не помещаться в надёжный carrier.

Поэтому появился `VISIBLE_TOKEN` fallback:

- если hidden carrier становится слишком большим;
- или чатовый сценарий становится небезопасным;
- система переходит на более компактный видимый token.

Это менее “магично”, но значительно устойчивее.

## Как работает clipboard decode

Clipboard decode в рамках IME возможен только пока жива сама клавиатура.

Это означает:

- если IME уже поднята, listener может увидеть `Copy`;
- если IME полностью выгружена, слушать буфер просто некому.

Из-за этого текущая архитектура делает упор на:

- decode в открытой клавиатуре;
- pending decode при следующем открытии;
- осторожные fallback-сценарии.

Для truly-always-on clipboard decode нужен уже отдельный app-level компонент вне `InputMethodService`.

## Как работает AI rewrite

### Источник текста

AI не берёт текст напрямую “из головы”. Сначала [AiDraftResolver.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/ai/AiDraftResolver.java) пытается получить полный draft:

1. `ExtractedText`;
2. safe surrounding fallback.

Это та же проблема, что и у invisible encode: IME не всегда видит весь черновик.

### Сетевой слой

[AiRewriteClient.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/ai/AiRewriteClient.java) поддерживает:

- `OpenAI`
- `OpenRouter`
- `Ollama`
- `Yandex`

Что уже сделано для устойчивости:

- если URL без схемы, автоматически подставляется `http://`;
- для `Ollama` автоматически дописывается `/api/chat`;
- для OpenAI/OpenRouter дописывается `/chat/completions`;
- для Yandex дописывается `/foundationModels/v1/completion`.

### Поведение

Есть два AI-сценария:

1. `rewrite before send`
2. `rewrite only`

Для чатов более надёжен `rewrite only`, который вызывается через long-press на `Enter` и не зависит от того, вызывает ли мессенджер IME action.

## Основные сложности проекта

### 1. Android IME не владеет чужим текстом

Самая большая практическая сложность — клавиатура видит только то, что отдаёт `InputConnection`.

Из-за этого:

- длинный текст может читаться частично;
- выделение и surrounding text ведут себя по-разному в разных приложениях;
- Telegram, VK, браузеры и заметки дают разный уровень доступа.

### 2. Мессенджеры нормализуют invisible-символы

То, что корректно работает в чистом `EditText`, может ломаться в чате:

- hidden chars удаляются;
- carrier переносится;
- draft разбивается на несколько отправок;
- возникают пустые сообщения.

Отсюда появились:

- safe single-line режим;
- compact fallback;
- жёсткие проверки размера;
- отказ от опасного partial encode.

### 3. MIUI и старые устройства ломают UI не так, как AOSP

В проекте уже всплывали реальные проблемы:

- `StackOverflowError` внутри MIUI font measurement;
- нестабильное закрытие IME по `Back`;
- attached dialog, который открывался и закрывался мгновенно;
- падения на inflate/theme-attrs;
- проблемы с показом candidate area.

Из-за этого ряд решений пришлось делать не “красиво по учебнику”, а “устойчиво на реальных устройствах”.

### 4. CandidateView нельзя было считать “готовым”

В кодовой базе не было полноценного живого suggestion subsystem уровня AOSP. Поэтому пришлось строить свой candidate-strip слой и долго подгонять его:

- под lifecycle IME;
- под тему клавиатуры;
- под системные insets;
- под разные версии Android.

### 5. Большие сообщения не масштабируются линейно

У шифрования есть физический overhead:

- `salt`
- `nonce`
- `tag`
- envelope metadata
- carrier-specific expansion

Поэтому длинный текст невозможно превратить в “очень короткое” скрытое сообщение без смены формата или без ухудшения безопасности.

## Почему некоторые решения выглядят как “хитрости”

Потому что в Android IME это и есть реальные инженерные хитрости.

Примеры:

- использовать `ExtractedText` перед surrounding cache;
- делать self-check encode/decode в `AUTO` режиме;
- при закрытии IME прятать candidate row без лишней перерисовки;
- завершать composing-state перед replace;
- хранить pending decode до следующего открытия клавиатуры;
- разводить `rewrite only` и `rewrite + send`;
- выбирать cover-template не только по UX, но и по технической вместимости carrier.

Это не украшения, а защита от реальных runtime-проблем.

## Безопасность и приватность

Текущая модель такая:

- passphrase хранится локально;
- payload шифруется на устройстве;
- invisible-сообщения не отправляются на сервер проекта;
- AI-запросы уходят только в выбранный пользователем AI backend;
- clipboard decode и pending decode работают локально.

Надо помнить:

- если включён AI rewrite, текст уходит внешнему провайдеру;
- если используется `Ollama`, приватность зависит уже от домашнего сервера пользователя;
- если приложение-мессенджер вычищает hidden chars, клавиатура это не “починит” после факта.

## Где смотреть код в первую очередь

Если нужно быстро понять проект, смотри в таком порядке:

1. [LatinIME.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java)
2. [InvisibleMessageController.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/InvisibleMessageController.java)
3. [CompositeInvisibleCodec.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/CompositeInvisibleCodec.java)
4. [InvisibleTargetResolver.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/invisible/InvisibleTargetResolver.java)
5. [CandidatePresentationCoordinator.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/CandidatePresentationCoordinator.java)
6. [CandidateStripView.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/CandidateStripView.java)
7. [AiDraftResolver.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/ai/AiDraftResolver.java)
8. [AiRewriteClient.java](./app/src/main/java/rkr/simplekeyboard/inputmethod/latin/ai/AiRewriteClient.java)

## Текущее состояние проекта

Проект уже умеет:

- работать как обычная клавиатура;
- скрывать и расшифровывать сообщения;
- использовать cover templates и emoji marker;
- показывать decode в keyboard strip;
- переписывать черновик через AI;
- работать с несколькими AI-провайдерами.

Но проект всё ещё остаётся IME-проектом, а значит главные риски лежат не в “чистой логике”, а в интеграции с:

- конкретными мессенджерами;
- конкретными прошивками;
- ограничениями `InputConnection`;
- нестабильным lifecycle клавиатуры.

Поэтому дальнейшее развитие должно идти через две вещи:

1. app-specific hardening под реальные чаты;
2. аккуратное расширение без возврата к хрупким overlay/popup путям.
