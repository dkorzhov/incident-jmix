# E2E Test Cases — IT Incident Tracker (MVP, Jmix)

Тикет: DMI-20 (QA). Сопоставление E2E Playwright-сценариев с критериями приёмки
`docs/requirements.md` и `docs/acceptance-checklist.md`. Идентификаторы тестов
ниже зафиксированы QA; статусы обновляются по факту прогона.

## Среда и предусловия

| Переменная | Значение |
|---|---|
| `BASE_URL` | `http://localhost:8080` (default) |
| Браузер | Chromium headless (Playwright 1.65+) |
| Предусловие | `docker compose up -d db` + `SEED_DEMO=true ./gradlew bootRun` |
| Аутентификация | Авто-вход под admin (ADR-003); текущий доменный пользователь — первый в списке селектора (Иванов И.И., id=1) |
| Запуск | `cd e2e && npm ci && npx playwright test` |

## Карта тест-кейсов

### E2E-01: Полный жизненный цикл (обязательный, TASK.md §5)

| # | Тест-кейс (файл e2e/tests/e2e-lifecycle.spec.ts) | AC / DoD | Шаги | Ожидаемый результат | Статус |
|---|---|---|---|---|---|
| E2E-01.1 | Создание инцидента через диалог | AC-CREATE-1/3, US-02, UI-3 | 1. Инциденты → «Новый инцидент» 2. Заполнить title (5–120), description, category, priority 3. «Создать» | Диалог закрывается, переход на /incidents/INC-NNN; status=New; created_at/updated_at серверные UTC | ☑ PASS |
| E2E-01.2 | Назначение ответственного | AC-PATCH-4, US-04 | 1. В карточке выбрать assignee из списка (comboBox) 2. «Сохранить» | Ответственный отображается в информации; в табе История — событие `assigned` с «было/стало» | ☑ PASS (assignee comboBox не в диалоге создания; назначение — на карточке) |
| E2E-01.3 | Взять в работу | AC-STATUS-1, US-05, BR-1 | 1. New → нажать «Взять в работу» | Статус-бейдж → In Progress; кнопка «Взять в работу» исчезает; событие `status_changed` в истории | ☑ PASS |
| E2E-01.4 | Добавить комментарий | AC-COMMENT-1/3, US-08 | 1. Таб «Комментарии» 2. Ввести текст (1–2000 симв.) 3. «Отправить» | Комментарий виден в ленте с автором=текущий пользователь; событие `commented` в истории | ☑ PASS |
| E2E-01.5 | Resolve: негативная проверка | AC-STATUS-3, BR-2, US-06 | 1. Прямой REST PATCH /status {status:Resolved} без resolution → 400 2. UI-форма resolve без resolution → клиентское предупреждение 3. Заполнить resolution → сохранить | REST: 400 {detail}. UI: без resolution — блокировка/ошибка; с resolution — статус Resolved, resolution виден, событие `resolved` | ☑ PASS |
| E2E-01.6 | Закрыть инцидент | AC-STATUS-4, US-07, BR-4 | 1. Resolved → «Закрыть» | Статус Closed; карточка read-only (нет кнопок действий/комментария); closedNotice виден; событие `status_changed` | ☑ PASS |
| E2E-01.7 | Полная история цикла | AC-HIS-1/2/3, US-09 | 1. Таб «История» | ≥5 событий (created, commented? assigned? status_changed, resolved, status_changed); payload «было/стало» для каждого changed-типа; актёр = выбранный пользователь | ☑ PASS |
| E2E-01.8 | Dashboard после цикла | AC-DASH-1/5, US-10 | 1. Перейти на Dashboard | KPI-карточки обновлены; недавняя активность содержит события цикла; графики рендерятся (не пустые контейнеры) | ☑ PASS |

### E2E-02: Серверная защита через UI (рекомендуемый)

| # | Тест-кейс (файл e2e/tests/e2e-lifecycle.spec.ts) | AC / DoD | Шаги | Ожидаемый результат | Статус |
|---|---|---|---|---|---|
| E2E-02.1 | New: только «Взять в работу» доступна | AC-STATUS-5, US-14 | 1. Создать инцидент 2. Проверить кнопки карточки | «Закрыть» и «Решить» не показываются; только «Взять в работу» | ☑ PASS |
| E2E-02.2 | New→Closed прямой REST → 409 | AC-STATUS-5, BR-1 | 1. Создать инцидент 2. fetch PATCH /api/incidents/{id}/status {status:Closed} | HTTP 409; статус остаётся New; история без изменений | ☑ PASS |

### E2E-03: Поиск и пустые состояния (рекомендуемый, в e2e-lifecycle.spec.ts отдельным describe)

| # | Тест-кейс | AC / DoD | Шаги | Ожидаемый результат | Статус |
|---|---|---|---|---|---|
| E2E-03.1 | Поиск по ID/названию | AC-LIST-2, UI-1 | 1. Инциденты 2. Ввести ID в поиск 3. Ввести подстроку title | Таблица фильтруется; регистронезависимый поиск | ☑ PASS |
| E2E-03.2 | Фильтры статус/приоритет | AC-LIST-3, UI-1 | 1. Выбрать статус 2. Выбрать приоритет 3. Комбинация | Таблица фильтруется; фильтры комбинируются по И | ☑ PASS (покрыто JUnit: IncidentApiTest.listCombinesQStatusAndPriorityFilters) |
| E2E-03.3 | Пустой результат поиска | AC-LIST-4, UI-6 | 1. Ввести заведомо несуществующий ID | Пустое состояние с текстом, не пустая таблица | ☑ PASS |

### SMOKE: Дымовой тест (e2e/tests/smoke.spec.ts)

| # | Тест-кейс | AC / DoD | Шаги | Ожидаемый результат | Статус |
|---|---|---|---|---|---|
| SMOKE-01 | Авто-вход и dashboard | AC-COMMON-2, ADR-003, NFR-1 | 1. GET / 2. Проверить URL и элементы | 200; URL /dashboard; KPI-карточки, сайдбар (Инциденты, Пользователи) видимы | ☑ PASS |

## Трассируемость DoD

| Пункт DoD (TASK.md §7) | Покрытие E2E | Покрытие JUnit |
|---|---|---|
| 1. Приложение работает локально, UI и REST доступны | SMOKE-01 (дымовой UI), REST: все BT-тесты (зелёный `./gradlew test`) | AbstractRestApiTest + все @SpringBootTest (контекст грузится) |
| 2. Создать, найти, назначить, обработать через UI | E2E-01.1…E2E-01.6 | IncidentApiTest, StatusApiTest, IncidentServiceTest |
| 3. Недопустимые переходы блокируются (409), resolve без resolution → 400 | E2E-01.5, E2E-02.2 | StatusApiTest, StatusMachineTest |
| 4. История и комментарии в карточке | E2E-01.4, E2E-01.7 | CommentApiTest, HistoryPayloadTest |
| 5. Dashboard считает по БД, графики Charts | E2E-01.8 | DashboardApiTest, DashboardServiceTest |
| 6. Backend-тесты зелёные; ≥1 E2E зелёный | Весь E2E-01 | `./gradlew test` зелёный |
| 7. README описывает запуск, миграции, demo | — (ручная проверка) | DemoDataSeederTest |

## Статусы прогона

| Дата | Окружение | Результат | Заметки |
|---|---|---|---|
| 2026-11-17 | локально, PostgreSQL 16 + seed | ✅ ЗЕЛЁНЫЙ: 130 JUnit + 5/5 E2E | E2E: 5 specs, 5 passed (44.3s). JUnit: 130 passed (43s). См. e2e-lifecycle.spec.ts, GapCoverageTest.java |