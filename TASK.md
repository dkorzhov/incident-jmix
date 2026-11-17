# TASK.md — IT Incident Tracker (MVP, Jmix)

Рабочая спецификация. Оригинал концепции: `docs/IT_Incident_Tracker_Концепция_MVP.docx`,
визуальный макет: `docs/ui-concept.png`. При расхождении приоритет у концепции.
Проект — эксперимент по агентной разработке: несколько агентов проходят полный цикл
(анализ → архитектура → backend → frontend → QA).

Это Jmix-версия проекта `incident` (FastAPI + React): требования и бизнес-правила
identical, отличается только стек реализации.

## 0. Стек (зафиксирован для этого проекта)

- **Jmix 3.1** (Spring Boot 4, Vaadin 25 Flow UI, EclipseLink), **Java 21**, сборка **Gradle**.
- БД: **PostgreSQL 16** (docker compose). Миграции: **Liquibase** (встроен в Jmix, применяются при старте).
- UI: **Jmix Flow UI** (Java-контроллеры + XML-дескрипторы), без отдельного frontend-приложения.
- Графики: **Jmix Charts add-on** (`io.jmix.charts:jmix-charts-flowui-starter`, Apache ECharts).
- REST API: кастомные Spring `@RestController` внутри того же приложения (контракт §3).
- Тесты: **JUnit 5** + Jmix integration tests / `jmix-flowui-test-assist`; **Playwright** (e2e/).
- Запуск: `docker compose up -d db` + `./gradlew bootRun`.

## 1. Предметная область

### Incident

| Поле | Тип | Описание |
|---|---|---|
| id | string `INC-NNN` | человекочитаемый, последовательный (INC-001…), генерирует сервер |
| title | string, 5–120 симв. | «Не работает почта» |
| description | string, 0–2000 симв. | детали |
| category | enum | `Network`, `Mail`, `Portal`, `Database`, `Workplace`, `Other` |
| priority | enum | `Critical`, `High`, `Medium`, `Low` |
| status | enum | `New`, `In Progress`, `Resolved`, `Closed` |
| assignee_id | int \| null | ответственный из списка пользователей |
| resolution | string \| null, 0–2000 симв. | описание решения; обязательно при переходе в Resolved |
| created_at / updated_at | datetime UTC | серверное время |

### User (предзагруженный список)

id, name, role (`engineer`, `manager`). Текущий пользователь выбирается в UI из списка
(упрощение MVP вместо аутентификации). Передаётся на backend заголовком `X-User-Id`.
В Jmix способ реализации селектора и «текущего пользователя» без аутентификации
фиксируется в ADR (например, отключение standard login + подстановка пользователя в сессию).

### Comment

id, incident_id, author_id, text (1–2000 симв.), created_at.

### HistoryEvent (пишется автоматически)

id, incident_id, actor_id, type (`created`, `assigned`, `status_changed`, `priority_changed`, `commented`, `resolved`, `fields_updated`), payload (json: было/стало), created_at.

## 2. Бизнес-правила (проверяются на backend)

```
New         → In Progress   («Взять в работу»)
In Progress → Resolved      (обязателен непустой resolution)
Resolved    → Closed        («Закрыть»)
```

- Нельзя закрыть напрямую из New или In Progress; иные переходы → **409**.
- Переход в Resolved без resolution → **400**.
- После Closed incident неизменяем: PATCH, смена статуса, комментарии → **409**.
- **Удаление инцидентов не предусмотрено** (DELETE-эндпоинта нет).
- Каждое значимое изменение автоматически пишется в историю.
- Валидация полей → **400** с указанием полей и причин.

## 3. REST API (кастомные @RestController в Jmix-приложении)

```
GET    /api/incidents              список: ?q= (поиск по ID и title), ?status=, ?priority=;
                                   сортировка created_at desc
POST   /api/incidents              создать: title, description, category, priority, assignee_id?
GET    /api/incidents/{id}         карточка + comments + history
PATCH  /api/incidents/{id}         title, description, category, priority, assignee_id
PATCH  /api/incidents/{id}/status  { status, resolution? } — по автомату §2
POST   /api/incidents/{id}/comments { text } — author = текущий пользователь
GET    /api/dashboard              counts: open, new, in_progress, critical, resolved_today;
                                   by_priority; recent_activity (последние 10 событий истории)
GET    /api/users                  список пользователей
```

Ответы — snake_case. Ошибки — JSON `{ detail: ... }` с понятным текстом.
Эндпоинты `/api/**` доступны без аутентификации, текущий пользователь — заголовок
`X-User-Id` (валидируется против списка users). Контракт идентичен проекту `incident`.

## 4. UI (Jmix Flow UI, визуальный ориентир — docs/ui-concept.png)

- Layout: тёмный сайдбар (Дашборд, Инциденты, Пользователи), селектор текущего пользователя в шапке.
- **Реестр**: KPI-карточки, поиск по ID/названию, фильтры статус/приоритет, таблица
  (ID, название, приоритет-бейдж с цветом, статус-бейдж, ответственный), кнопка «Новый инцидент».
- **Карточка**: номер+название, бейджи, табы «Информация / История / Комментарии»;
  действия только допустимых переходов; форма resolve с обязательным resolution; смена assignee.
- **Создание**: title, description, category, priority, assignee; клиентская валидация длины title;
  ошибки сервера — понятным текстом.
- **Dashboard**: KPI (открытые, новые, в работе, критические, решено сегодня), диаграмма по приоритетам
  (Charts add-on), график динамики (Charts add-on), недавняя активность.
- **Пользователи**: список пользователей (просмотр).
- Пустые состояния, обработка ошибок — не молча.

## 5. Тесты

- Backend (JUnit 5 + Jmix test support): ≥1 тест на каждый эндпоинт; ≥3 на автомат
  (успех, 409, обязательность resolution); неизменяемость Closed; автозапись истории;
  сходимость dashboard с БД. БД — отдельная тестовая схема/контейнер (Testcontainers или HSQLDB в тестах).
- E2E (Playwright, ≥1 сценарий): создать → назначить → взять в работу → resolve с resolution →
  закрыть; комментарий и история видны в карточке.
- UI: сборка обязательна (`./gradlew build`); UI integration tests (`jmix-flowui-test-assist`) приветствуются.

## 6. Запуск и данные

- `docker compose up -d db` поднимает PostgreSQL 16; `./gradlew bootRun` — приложение (UI + REST).
- Миграции — Liquibase (changelog в `src/main/resources/<package>/liquibase`), применяются при старте.
- Demo-seed: пользователи (3–5), несколько инцидентов в разных статусах
  (включая Critical и решённый сегодня) — включается переменной `SEED_DEMO=true`.
- README: запуск, миграции, demo-данные, тесты.

## 7. Definition of Done

- [ ] Приложение работает локально (db + bootRun), UI и REST доступны.
- [ ] Создать, найти, назначить, обработать инцидент через UI.
- [ ] Недопустимые переходы блокируются сервером (409), resolve без resolution — 400.
- [ ] История и комментарии отображаются в карточке.
- [ ] Dashboard считает показатели по данным БД, графики рендерятся Charts add-on.
- [ ] Backend-тесты зелёные одной командой; ≥1 E2E-сценарий зелёный.
- [ ] README описывает запуск, миграции и демонстрационные данные.

## 8. Что НЕ входит в MVP

Аутентификация/SSO/роли; **SLA и эскалации**; интеграции (почта/мессенджеры);
AI-классификация; Problem/Change/CI; удаление инцидентов; пагинация.
