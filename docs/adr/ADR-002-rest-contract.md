# ADR-002: REST-контракт `/api/**` (кастомные @RestController)

- **Статус:** Accepted
- **Дата:** 2026-11-17
- **Тикет:** DMI-15
- **Связанные документы:** `TASK.md` §2–3, `docs/requirements.md` §5 (AC-*), `docs/questions.md` (Q-06…Q-10, Q-15), ADR-001 (схема), ADR-003 (без аутентификации), ADR-005 (сервисы)

## Контекст

Контракт идентичен проекту `incident` (FastAPI + React): snake_case, ошибки
`{detail}`, коды 400/404/409, текущий пользователь — заголовок `X-User-Id`.
Эндпоинты живут в том же Jmix-приложении, что и Flow UI. Generic REST add-on
Jmix не используется: его формат (fetchPlan, обёртки, camelCase) не совпадает с
контрактом, а настройка под контракт дороже, чем тонкие контроллеры поверх
сервисного слоя.

## Решение

### 1. Транспорт и структура

- Кастомные Spring `@RestController` в пакете `com.company.incidentjmix.rest`:
  `IncidentController` (`/api/incidents`), `DashboardController` (`/api/dashboard`),
  `UserController` (`/api/users`). Контроллеры тонкие: разбор запроса → вызов
  сервиса (ADR-005) → маппинг в DTO. Бизнес-логика в контроллерах запрещена (NFR-7).
- DTO — Java records в `rest/dto` с
  `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` (Jackson;
  Spring Boot 4 автоконфигурирует ObjectMapper). Ответы — только DTO, не сущности.
- Даты — ISO-8601 UTC (`OffsetDateTime`, суффикс `Z`/`+00:00`); Jackson
  `WRITE_DATES_AS_TIMESTAMPS=false` (Boot default).
- Неизвестные поля тела **игнорируются** (Boot default
  `FAIL_ON_UNKNOWN_PROPERTIES=false`); клиентский `id` в POST игнорируется
  (Q-02/Q-15: id всегда генерирует сервер).

### 2. Security для `/api/**`

В `security/IncidentJmixSecurityConfiguration` добавить отдельный bean
`apiFilterChain` (по образцу существующего `publicFilterChain`):

```java
@Bean
@Order(JmixSecurityFilterChainOrder.CUSTOM)
SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**");
    http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
    http.csrf(csrf -> csrf.disable());
    JmixHttpSecurityUtils.configureAnonymous(http);
    return http.build();
}
```

Проверенные символы: `JmixSecurityFilterChainOrder.CUSTOM` (уже используется в
шаблоне), `io.jmix.security.util.JmixHttpSecurityUtils.configureAnonymous`
(docs «Custom endpoints», javadoc). Matcher'ы `/public/**` и `/api/**` не
пересекаются, порядок между chain'ами с одинаковым `@Order` не влияет.

`configureAnonymous` выставляет anonymous-пользователя Jmix в security context.
Anonymous **не имеет прав на доменные сущности**, поэтому контроллеры выполняют
операции через `SystemAuthenticator` (`io.jmix.core.security.SystemAuthenticator`,
подтверждён javadoc):

```java
return systemAuthenticator.withSystem(() -> incidentService.create(actor, ...));
```

Доменный актёр (`AppUser`) передаётся в сервисы **явным параметром** — системная
аутентификация нужна только для доступа к данным, не для идентификации актёра
(actor_id в истории = X-User-Id / выбранный в UI пользователь, AC-HIS-3).

### 3. X-User-Id (Q-06, вариант c)

- Разбор заголовка — `HandlerMethodArgumentResolver` (собственный класс в `rest/`),
  резолвящий параметр контроллера типа `AppUser`, помеченный собственной
  аннотацией `@CurrentAppUser(optional = …)`. Валидация — против `AppUserService`
  (тот же список, что `GET /api/users`, AC-USERS-3).
- **Mutating (POST, PATCH):** заголовок обязателен; отсутствует, не число или
  неизвестный id → **401** `{detail}` до любых изменений данных.
- **GET:** отсутствие заголовка допустимо; присутствующий, но неизвестный → **401**.
- Ошибка 401 — доменное `UnknownUserException` (ADR-005), маппится advice'ом.

### 4. Эндпоинты

| Метод | Путь | Успех | Тело ответа |
|---|---|---|---|
| GET | `/api/incidents` | 200 | массив incident DTO |
| POST | `/api/incidents` | **201** | созданный incident DTO |
| GET | `/api/incidents/{id}` | 200 | incident DTO + `comments[]` + `history[]` |
| PATCH | `/api/incidents/{id}` | 200 | актуальный incident DTO |
| PATCH | `/api/incidents/{id}/status` | 200 | актуальный incident DTO |
| POST | `/api/incidents/{id}/comments` | **201** | созданный comment DTO |
| GET | `/api/dashboard` | 200 | dashboard DTO |
| GET | `/api/users` | 200 | массив `{id, name, role}` |
| DELETE | `/api/incidents/{id}` | **405** | `{detail}` (Q-07: фиксируем 405) |

DELETE-эндпоинта нет; Spring MVC на DELETE к существующему пути отвечает
`HttpRequestMethodNotSupportedException` → advice маппит в 405 `{detail}`
(данные не удаляются никогда).

**Incident DTO** (список, карточка, ответы мутаций):
`id, title, description, category, priority, status, assignee_id, resolution,
created_at, updated_at` (AC-LIST-1). Имена пользователей в incident DTO не
включаются — UI берёт их из `GET /api/users` (единый источник).

**Comment DTO:** `id, incident_id, author_id, text, created_at`.
**HistoryEvent DTO:** `id, incident_id, actor_id, type, payload, created_at`;
`payload` — JSON-объект (в БД хранится строкой, ADR-001; маппер десериализует).

**GET /api/incidents/{id}:** одним запросом инцидент + `comments`
(хронологически, `created_at asc, id asc`) + `history` (новые сверху,
`created_at desc, id desc`) — Q-15/AC-GET-3. Неизвестный id → 404 (Q-07).

**GET /api/incidents:**
- `?q=` — регистронезависимая подстрока по `id` ИЛИ `title`;
- `?status=`, `?priority=` — точные enum-значения; комбинируются с `q` по И;
  неизвестное значение → **400** с указанием параметра (Q-15);
- сортировка `created_at desc, id desc`; без пагинации; пустой результат → 200 `[]`.

**POST /api/incidents:** тело `title, description?, category, priority,
assignee_id?`. Валидация (ADR-005): title 5–120 после trim, description ≤2000,
category/priority обязательны и из enum, assignee_id ∈ users (иначе 400 с
перечислением полей). `status=New`, `created_at=updated_at`=серверное UTC,
id — следующий `INC-NNN` (ADR-001). Ровно одно событие `created`.

**PATCH /api/incidents/{id}:** частичное обновление — в DTO-запросе поля
обёрнуты так, чтобы различать «отсутствует» и «null» (реализация: `JsonNode`
или record с `Optional`/присутствием — выбирает backend; семантика фиксирована):
- отсутствие поля = не менять; `assignee_id: null` = снять назначение (AC-PATCH-3);
- допустимые поля: `title, description, category, priority, assignee_id`;
- no-op (значения совпадают с текущими) → 200 без событий и без обновления
  `updated_at` (Q-09);
- история: `assigned` / `priority_changed` / `fields_updated` — по одному
  событию на тип, `fields_updated` агрегирует только изменённые
  title/description/category (§6 requirements, HIS-1).

**PATCH /api/incidents/{id}/status:** тело `{status, resolution?}`.
- Автомат: New→In Progress; In Progress→Resolved (непустой resolution ≤2000);
  Resolved→Closed. Иные переходы, переход в тот же статус, любой переход из
  Closed → **409** (AC-STATUS-5/7).
- Неизвестное значение `status`, resolution >2000, отсутствие/пустой/пробельный
  resolution при переходе в Resolved → **400** (не 409; AC-STATUS-3/6).
- **resolution write-once** (Q-08): сохраняется только при переходе в Resolved,
  далее read-only; передача `resolution` при переходе **не в Resolved
  игнорируется** (фиксируем вариант «игнорируется», AC-STATUS-9); reopen из
  Resolved/Closed → 409 (вне MVP).
- История: New→In Progress и Resolved→Closed → `status_changed`;
  In Progress→Resolved → **только `resolved`** (без дублирующего
  `status_changed`, HIS-2), payload включает статус и resolution.

**POST /api/incidents/{id}/comments:** тело `{text}` (1–2000 после trim);
author = текущий пользователь из X-User-Id, **не из тела**; Closed → 409;
событие `commented` с payload `{after:{comment_id, text}}`. Комментарий **не
меняет** `updated_at` инцидента (Q-09).

**GET /api/users:** массив `{id, name, role}`, сортировка по id; тот же список,
что в селекторе UI и валидации заголовка/assignee (AC-USERS-2).

### 5. GET /api/dashboard

```json
{
  "counts": { "open": 4, "new": 2, "in_progress": 2, "critical": 2, "resolved_today": 1 },
  "by_priority": [ { "priority": "Critical", "count": 2 }, { "priority": "High", "count": 1 } ],
  "recent_activity": [
    { "incident_id": "INC-005", "incident_title": "…", "actor_id": 1,
      "actor_name": "Иванов И.И.", "type": "resolved",
      "payload": { "before": {…}, "after": {…} }, "created_at": "…" }
  ],
  "dynamics": [ { "date": "2026-11-11", "created": 1, "resolved": 0 }, … ]
}
```

- `counts` (Q-05): `open` = статус ≠ Closed; `new` = New; `in_progress` =
  In Progress; `critical` = Critical ∧ ≠ Closed; `resolved_today` = события
  истории `resolved` с `created_at` ∈ текущие сутки UTC (Q-04 a; инцидент,
  решённый и затем закрытый сегодня, учитывается).
- `by_priority` — по **открытым** (≠ Closed), сумма = `open`; массив
  `{priority, count}` в фиксированном порядке Critical, High, Medium, Low,
  **только ненулевые** записи (пустая БД → `[]`, AC-DASH-6). UI/Charts дополняет
  нулевыми категориями при необходимости.
- `recent_activity` — ≤10 событий истории по всем инцидентам,
  `created_at desc, id desc`; поля `incident_id, incident_title, actor_id,
  actor_name, type, payload, created_at` (actor_name/incident_title — для ленты
  без дополнительных запросов, Q-05).
- **`dynamics`** — отдельное поле ответа (фиксируем предложение Q-05, AC-DASH-4
  допускает API или UI): последние **7 суток UTC включая сегодня**, по возрастанию
  даты; `created` = инциденты с `created_at` в эти сутки, `resolved` = события
  `resolved` в эти сутки; бакет — сутки UTC; дни без событий присутствуют с нулями.
- Агрегаты считает `DashboardService` запросами к БД (JPQL/`dataManager.loadValues`,
  ADR-005), не выгрузкой всех сущностей; чтение не пишет историю (AC-DASH-7).

### 6. Ошибки — единый формат `{detail}`

`@RestControllerAdvice(assignableTypes = {IncidentController.class,
DashboardController.class, UserController.class})` в `rest/RestExceptionHandler`
(ограничение по контроллерам, чтобы не перехватывать обработчики фреймворка).
`detail` — человекочитаемая строка (Q-10), для 400 — перечисление
«поле: причина; поле: причина». UI выводит текст как есть (notification).

| Исключение | HTTP |
|---|---|
| доменное `ValidationException` (сервисный слой) | 400 |
| `HttpMessageNotReadableException` (битый JSON, неизвестное enum-значение в теле) | 400 |
| `MethodArgumentTypeMismatchException` / невалидный фильтр в query | 400 |
| доменное `UnknownUserException` (X-User-Id) | 401 |
| доменное `NotFoundException` (incident id) | 404 |
| доменное `ConflictException` (переход автомата, Closed неизменяем) | 409 |
| `ObjectOptimisticLockingFailureException` (конкурентная правка, @Version) | 409 |
| `HttpRequestMethodNotSupportedException` (DELETE и пр.) | 405 |
| прочее `Exception` | 500 `{detail}` (лог сервера, без внутренних деталей клиенту) |

При любой ошибке данные и история не меняются: сервис выполняет проверку и
запись в одной транзакции, исключение откатывает всё (AC-HIS-1).

## Альтернативы

1. **Jmix REST add-on (generic endpoints).** Отклонено: другой формат ответов,
   fetchPlan-семантика, собственная security-модель; подгонка под контракт
   `incident` дороже кастомных контроллеров. Кастомные `@RestController` —
   рекомендованный Jmix путь для нестандартного API (docs «Custom endpoints»).
2. **Аутентификация в `/api/**` (token/basic).** Отклонено: вне MVP (§8),
   контракт требует X-User-Id.
3. **DELETE → 404.** Отклонено в пользу 405 (Q-07): ресурс существует, метод
   не поддерживается — семантически точнее и стабильнее для тестов.
4. **resolution при не-Resolved переходе → 400.** Отклонено в пользу
   «игнорируется» (Q-08): мягче к клиенту, не ломает UI-форму, которая шлёт
   поле всегда; write-once всё равно защищает от перезаписи.
5. **`dynamics` вычисляет UI из списка инцидентов.** Отклонено: UI не видит
   историю всех инцидентов одним запросом; серверный агрегат дешевле и тестируем
   на сходимость с БД (BT).
6. **Хранить actor_name в DTO инцидента/комментария.** Отклонено: контракт
   `incident` содержит только id-поля; имена — из `/api/users`. Исключение —
   `recent_activity` (лента событий, Q-05 явно предлагает actor_name).

## Последствия

- Backend реализует контроллеры, DTO-мапперы, advice, argument resolver и
  security-chain; все доменные исключения приходят из сервисов (ADR-005) —
  HTTP-коды не «размазаны» по контроллерам.
- QA: ≥1 тест на эндпоинт, ≥3 на автомат, 400/409-различение, 401-матрица
  заголовка, DELETE→405, сходимость dashboard (BT по acceptance-checklist).
- UI ходит в те же сервисы напрямую (не через HTTP) — контракт и UI не могут
  разойтись в бизнес-правилах (NFR-7).
- `SystemAuthenticator.withSystem` в контроллерах — обязательный паттерн:
  без него DataManager anonymous-запроса упадёт на правах доступа; новые
  эндпоинты должны следовать ему (и skill `jmix-run-background-code` для
  любого кода вне аутентифицированного запроса).
- Символы Jackson/Spring (`@JsonNaming`, `PropertyNamingStrategies`,
  `@RestControllerAdvice`, `HandlerMethodArgumentResolver`) — стандартный
  Spring Boot 4 стек; при реализации backend проверяет точные сигнатуры по
  skill `jmix-verify-api-symbol` (javap/classpath), Jmix-символы выше уже
  проверены по docs/javadoc.
