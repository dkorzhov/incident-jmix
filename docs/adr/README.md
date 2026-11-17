# ADR — IT Incident Tracker (Jmix MVP)

Архитектурные решения этапа architect (DMI-15). Источник требований —
`TASK.md` и `docs/requirements.md` (DMI-14); открытые вопросы аналитика
(`docs/questions.md`, Q-01…Q-15) **зафиксированы** в этих ADR.

Структура каждого ADR: контекст → решение → альтернативы → последствия.

| ADR | Тема | Ключевые решения |
|---|---|---|
| [ADR-001](ADR-001-database-schema.md) | Схема БД и сущности | Отдельный доменный `AppUser` (int id, role), `inc_User` остаётся техническим; Incident со строковым PK `INC-NNN`; номер — таблица-счётчик под пессимистической блокировкой (без пропусков); Comment/HistoryEvent — Integer IDENTITY; payload — JSON-строка (HSQLDB-совместимость); changelog'и 020–050, пользователи создаются всегда |
| [ADR-002](ADR-002-rest-contract.md) | REST-контракт `/api/**` | Кастомные `@RestController` (не REST add-on); snake_case DTO; `{detail}`; 400/401/404/405/409; X-User-Id (mutating обязателен, GET опционален); `/api/**` permitAll + `JmixHttpSecurityUtils.configureAnonymous` + `SystemAuthenticator.withSystem`; dashboard с `dynamics` (7 суток UTC) |
| [ADR-003](ADR-003-no-auth-mvp.md) | MVP без аутентификации | UI авто-входит под техническим admin через `LoginViewSupport`; доменный текущий пользователь — селектор в шапке MainView + `SessionData` (default — первый по id); актёр — явный параметр сервисов; admin/admin остаётся техническим демо-аккаунтом |
| [ADR-004](ADR-004-flow-ui.md) | Flow UI | 4 views (dashboard, реестр, карточка с табами + создание в диалоге, пользователи); `jmix.ui.default-view-id=inc_DashboardView`; меню без «Настройки»; мутации только через сервисы (не стандартный save); Charts add-on (`chartsflowui`, pie + line); бейджи UI-7, время UTC с пометкой, ru/en |
| [ADR-005](ADR-005-services-history-seed.md) | Сервисы, история, seed | Единый сервисный слой для UI и REST; история пишется сервисами явно (не entity listener), payload по таблице §6 requirements; доменные исключения → HTTP-коды; `SEED_DEMO` — идемпотентный seeder через сервисы под `SystemAuthenticator` |

## Фиксация вопросов аналитика (Q-01…Q-15)

| Вопрос | Решение | Где |
|---|---|---|
| Q-01 | Вариант A: отдельный `AppUser`, техническая авто-аутентификация UI | ADR-001 §1, ADR-003 |
| Q-02 | Строковый PK; счётчик под блокировкой (не `max(id)+1`, не sequence с пропусками); `INC-%03d` без верхнего предела; клиентский id игнорируется | ADR-001 §2–3, ADR-002 §1 |
| Q-03 | Гранулярность истории — таблица §6 requirements принята полностью | ADR-005 §4 |
| Q-04 | Вариант a: `resolved_today` по событиям `resolved` за сутки UTC, без новой колонки | ADR-002 §5, ADR-001 (альтернативы) |
| Q-05 | Формулы counts; by_priority по открытым; динамика 7 суток UTC — поле `dynamics` в API; recent_activity с actor_name/incident_title | ADR-002 §5 |
| Q-06 | Вариант c: mutating без/неизвестный X-User-Id → 401; GET допускает отсутствие, неизвестный → 401 | ADR-002 §3 |
| Q-07 | Неизвестный id → 404; DELETE → **405** | ADR-002 §4, §6 |
| Q-08 | resolution write-once; при не-Resolved переходе **игнорируется**; reopen → 409 | ADR-002 §4, ADR-005 §3 |
| Q-09 | `updated_at` — только при изменении собственных полей; комментарий/no-op не трогают | ADR-005 §3 |
| Q-10 | `detail` — человекочитаемая строка «поле: причина; …» | ADR-002 §6, ADR-005 §2 |
| Q-11 | «Настройки» исключены; сайдбар: Дашборд, Инциденты, Пользователи | ADR-004 §7 |
| Q-12 | Приоритет концепции — только бизнес-правила; стек/запуск/тестовая БД — по TASK.md | ADR-001 (контекст) |
| Q-13 | Default — первый по id; выбор в Vaadin-сессии (`SessionData`); актёр — параметр сервисов | ADR-003 §3 |
| Q-14 | 4 пользователя (Liquibase, всегда); 6 инцидентов seed через сервисы, идемпотентно | ADR-001 §5, ADR-005 §5 |
| Q-15 | POST/comments → 201; q — подстрока id+title без регистра; неизвестный фильтр → 400; сортировка created_at desc, id desc; неизвестные поля тела игнорируются; history новые сверху, comments хронологически; UI ru, UTC с пометкой | ADR-002 §1, §4; ADR-004 §7 |

## Порядок чтения для следующих этапов

- **backend:** ADR-001 → ADR-005 → ADR-002 → ADR-003 (security-chain).
- **frontend:** ADR-003 → ADR-004 → ADR-005 (контракты сервисов) → ADR-001 (модель).
- **qa:** ADR-002 (коды/формы) → ADR-005 (инварианты) → `docs/acceptance-checklist.md`.

## Проверка API

Jmix-символы, упомянутые в ADR, проверены по официальной документации Jmix 3
(MCP `jmix-docs`, docs.jmix.io, javadoc 3.0/3.1) и коду шаблона; источники
указаны в тексте. Символы, помеченные «frontend/backend проверяет», обязаны
пройти skill `jmix-verify-api-symbol` **до** написания кода. Каждый артефакт
реализуется по профильному skill из `.agents/skills/` (маршрутизация — skill
`jmix`); gates закрытия задачи — `jmix-ide-static-analysis`, `jmix-verify-bootrun`.

Ограничения этапа architect соблюдены: изменён только `docs/`, код не писался,
стек (Jmix 3.1 / Java 21 / PostgreSQL 16 / Liquibase / Flow UI / Charts) не менялся.
