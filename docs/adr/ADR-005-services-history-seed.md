# ADR-005: Сервисный слой, автозапись истории, demo-seed

- **Статус:** Accepted
- **Дата:** 2026-11-17
- **Тикет:** DMI-15
- **Связанные документы:** `TASK.md` §2, §6; `docs/requirements.md` (FR-3, FR-6, BR-1…BR-8, §6 payload, HIS-1…HIS-5, NFR-7), `docs/questions.md` (Q-03, Q-08, Q-09, Q-14); ADR-001 (сущности), ADR-002 (REST), ADR-003 (актёр), ADR-004 (UI)

## Контекст

Бизнес-правила (автомат статусов, неизменяемость Closed, валидация,
автоистория) должны проверяться на backend и быть **едиными для UI и REST**
(NFR-7). Каждое значимое изменение порождает ровно одно событие истории нужного
типа в той же транзакции (FR-3, AC-HIS-1). Актёр — доменный `AppUser`
(X-User-Id / селектор), не технический пользователь Jmix. Demo-seed включается
`SEED_DEMO=true` (`app.seed-demo`, свойство уже в `application.properties`).

## Решение

### 1. Каталог сервисов (`com.company.incidentjmix.service`)

Все сервисы — Spring-бины, работают через `DataManager` (skill
`jmix-create-service`), mutating-методы — `@Transactional`. UI- и REST-слои
вызывают одни и те же методы; контроллеры/view не содержат бизнес-логики.

| Сервис | Методы (семантика) |
|---|---|
| `AppUserService` | `list()` (сортировка id); `getById(Integer)`; `resolveActor(String headerValue, boolean required)` → `AppUser` либо `UnknownUserException`; `requireExisting(Integer id)` для валидации assignee. Единый источник пользователей для REST, селектора UI и валидации (AC-USERS-2). |
| `IncidentService` | `create(AppUser actor, CreateCommand)` → `Incident` (генерация INC-NNN по ADR-001 §3, status=New, created_at=updated_at=now UTC, событие `created`); `update(actor, String id, PatchCommand)` → `Incident` (частичное обновление, diff, события по типам); `changeStatus(actor, String id, IncidentStatus target, String resolution)` → `Incident` (автомат); `list(String q, IncidentStatus status, IncidentPriority priority)` (сортировка created_at desc, id desc); `get(String id)` (+ comments/history для карточки — fetch plan или отдельные загрузки). |
| `CommentService` | `add(actor, String incidentId, String text)` → `Comment` (проверка Closed, валидация text, событие `commented`; `updated_at` инцидента не меняется). |
| `HistoryService` | `record(Incident incident, AppUser actor, HistoryEventType type, Map<String,Object> payload)` — сериализация payload Jackson `ObjectMapper` → JSON-строка, `created_at` = now UTC, сохранение `HistoryEvent`. Вызывается **только сервисами** внутри их транзакций; публичных методов изменения/удаления истории нет (AC-HIS-4). |
| `DashboardService` | `getDashboard()` → DTO (counts, by_priority, recent_activity ≤10, dynamics 7 суток UTC) — агрегаты JPQL-запросами (`dataManager.loadValues("select … group by …").properties(…).list()` → `KeyValueEntity`, подтверждено docs «DataManager»), не выгрузкой всех сущностей. Чтение не пишет историю. |

Команды (`CreateCommand`, `PatchCommand`) — простые DTO сервисного слоя;
`PatchCommand` различает «поле отсутствует» и «null» (семантика AC-PATCH-3;
реализация — за backend). REST-мапперы и UI-формы строят команды из своих
источников.

### 2. Доменные исключения (`service/`, RuntimeException → откат транзакции)

| Исключение | Смысл | HTTP (ADR-002) |
|---|---|---|
| `ValidationException` | нарушения полей; хранит пары «поле → причина», message = `"title: длина 5–120 символов; priority: неизвестное значение"` (Q-10) | 400 |
| `UnknownUserException` | X-User-Id отсутствует (для mutating)/не число/неизвестен | 401 |
| `NotFoundException` | инцидент не найден | 404 |
| `ConflictException` | недопустимый переход, тот же статус, любое изменение Closed, reopen | 409 |

Проверки выполняются **до** записи; при исключении транзакция откатывается
целиком — данные и история не меняются (AC-HIS-1). Оптимистический конфликт
`@Version` маппится в 409 на REST-границе (ADR-002).

### 3. Валидация (единая, в сервисах)

- title: обязателен, после trim 5–120; description: ≤2000; resolution: ≤2000;
  comment text: обязателен, после trim 1–2000.
- category/priority/status: значения только из enum (`EnumClass.fromId`);
  неизвестное значение → `ValidationException` (400), **не** 409 (BR, AC-STATUS-6).
- assignee_id (если передан/non-null): существует в `AppUserService` (иначе 400).
- Автомат (`changeStatus`): Closed → 409 при любом целевом статусе;
  переход в тот же статус → 409; допустимы только New→In Progress,
  In Progress→Resolved, Resolved→Closed; остальное → 409.
  In Progress→Resolved без непустого (после trim) resolution → 400.
- resolution **write-once**: сохраняется только при переходе в Resolved;
  при переходах не в Resolved переданный resolution игнорируется (Q-08,
  зафиксировано в ADR-002); после сохранения — read-only.
- `updated_at` обновляется **только при фактическом изменении собственных
  полей инцидента**; комментарий его не трогает; no-op PATCH не трогает (Q-09).

### 4. Автозапись истории — в сервисах, НЕ entity listener

Решение: `IncidentService`/`CommentService` сами вычисляют diff и вызывают
`HistoryService.record(...)` в той же транзакции. Гранулярность и payload —
таблица §6 requirements (**Q-03 принят полностью**):

| Операция | Тип | payload |
|---|---|---|
| создание | `created` | `{after:{title, description, category, priority, status:"New", assignee_id}}` |
| смена assignee | `assigned` | `{before:{assignee_id}, after:{assignee_id}}` |
| New→In Progress, Resolved→Closed | `status_changed` | `{before:{status}, after:{status}}` |
| смена priority | `priority_changed` | `{before:{priority}, after:{priority}}` |
| правка title/description/category | `fields_updated` | `{before:{…только изменённые}, after:{…только изменённые}}` |
| In Progress→Resolved | `resolved` | `{before:{status:"In Progress", resolution:null}, after:{status:"Resolved", resolution}}` |
| комментарий | `commented` | `{after:{comment_id, text}}` |

Инварианты (HIS-1…HIS-5, AC-HIS):
- комбинированный PATCH → по одному событию на тип; title/description/category
  агрегируются в одно `fields_updated`;
- resolve → **только `resolved`**, без дублирующего `status_changed`;
- no-op → событий нет; GET → событий нет;
- actor и created_at заполняет сервер; порядок стабилен (`created_at`, `id`);
- payload «было/стало» содержит фактические значения до/после (diff считается
  от загруженного в транзакции состояния, не от клиентских данных).

Почему не listener:
1. **Тип события — бизнес-категория**, не выводимая из diff полей: смена
   статуса и resolve трогают одно поле `status`, но требуют разных типов;
   `assigned`/`fields_updated` — семантика операции, а не набора колонок.
2. **Актёр доменный**: listener видит техническую аутентификацию
   (admin/system), для AppUser понадобился бы ThreadLocal-контекст —
   скрытая связность вместо явного параметра.
3. Надёжность: `EntityManager`-доступ (счётчик, ADR-001) событий сущностей
   не шлёт (docs «EntityManager»); сервисный вызов истории не зависит от
   способа персистентности.
4. Тестируемость: правила и история проверяются юнит/интеграционно на
   сервисах без UI.

### 5. Demo-seed (`SEED_DEMO=true`)

- **Пользователи — всегда Liquibase** (`020-init-app-user.xml`, ADR-001):
  список нужен и без seed (FR-6, AC-USERS).
- **Инциденты — `DemoDataSeeder`** (`service/` или отдельный `bootstrap/`):
  Spring-бин, запускается после готовности контекста (ApplicationRunner /
  ApplicationReadyEvent — выбирает backend по skill `jmix-run-background-code`),
  только при `app.seed-demo=true` (свойство уже связано с `SEED_DEMO`).
- Контекст безопасности: на старте аутентифицированного пользователя нет —
  seeder выполняет работу в **`SystemAuthenticator.withSystem(...)`**
  (`io.jmix.core.security.SystemAuthenticator`, подтверждён javadoc; обязательный
  паттерн skill `jmix-run-background-code`, иначе DataManager упадёт
  «Authentication is not set»).
- **Идемпотентность**: если в БД есть хотя бы один инцидент — пропуск (BT-35).
- Создание — **через `IncidentService`/`CommentService`** с актёрами из списка
  пользователей: номера INC-NNN, история и счётчик согласованы автоматически
  (никаких ручных insert'ов инцидентов/истории в Liquibase).
- Состав (Q-14; финальные тексты/детали фиксирует backend, критерии — BT-35):
  6 инцидентов — New Critical «Проблемы с VPN»; New Medium; In Progress High
  «Не работает почта» (с комментарием); In Progress Critical «Медленно работает
  БД»; Resolved сегодня High (с resolution; событие `resolved` с created_at =
  момент seed = текущие сутки UTC); Closed Low (полный цикл
  created→assigned→status_changed→resolved→status_changed в истории).
  Переходы выполняются `changeStatus` — история полного цикла корректна.
- Seed не включён по умолчанию (`SEED_DEMO:false`); README описывает запуск.

## Альтернативы

1. **Entity listener (`EntitySavingEvent`/`EntityChangedEvent`) для истории.**
   Отклонено — причины в §4. Listener уместен для технической аудита, не для
   бизнес-событий с доменным актёром.
2. **Правила в REST-контроллерах + отдельные правила в UI.** Отклонено:
   дублирование и расхождение (NFR-7 прямо требует общий слой).
3. **Seed целиком в Liquibase (insert инцидентов/истории).** Отклонено:
   пришлось бы вручную писать payload JSON, счётчик и FK в SQL, поддерживать
   «resolved сегодня» динамическими датами; сервисный seed сам даёт корректную
   историю. Статичные пользователи в Liquibase — наоборот, правильно (FR-6).
4. **Seed при каждом старте с очисткой.** Отклонено: удаление данных запрещено
   (§8), идемпотентный пропуск безопаснее.
5. **`resolved_at` колонка вместо истории для метрик.** Отклонено в ADR-001
   (Q-04 a).

## Последствия

- Backend реализует сервисы/исключения/seeder по skill `jmix-create-service`
  (+ `jmix-run-background-code` для seeder, `jmix-configure-fetch-plan` для
  загрузок карточки/лент); каждый mutating-метод принимает актёра первым
  параметром — это контракт для UI (ADR-004) и REST (ADR-002).
- QA: ≥3 теста автомата (успех/409/400-resolution), неизменяемость Closed,
  автоистория (типы, payload, одна транзакция), no-op, конкурентные создания
  (BT-34), seed (BT-35), сходимость dashboard с БД — все против сервисов и
  REST на HSQLDB.
- История неизменяема: сервисов/эндпоинтов правки истории нет; payload —
  JSON-строка (ADR-001), наружу отдаётся объектом.
- Единственная точка сериализации записи — строка счётчика (ADR-001);
  сервисы не должны вызывать `create` в цикле без необходимости (seed — 6
  инцидентов, несущественно).
- При переходе к реальной аутентификации меняется только способ получения
  актёра (параметр остаётся), сервисы и история не переписываются.
