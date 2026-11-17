# ADR-001: Схема БД и модель сущностей Jmix

- **Статус:** Accepted
- **Дата:** 2026-11-17
- **Тикет:** DMI-15
- **Связанные документы:** `TASK.md` §1–2, `docs/requirements.md` (FR-1…FR-12, §6), `docs/questions.md` (Q-01, Q-02, Q-04, Q-12, Q-14)

## Контекст

Нужна персистентная модель для Incident, Comment, HistoryEvent и предзагруженного
списка пользователей. Ограничения:

- Jmix 3.1 (EclipseLink), PostgreSQL 16 в проде, **HSQLDB в backend-тестах** — схема
  и типы должны быть совместимы с обеими БД; миграции — Liquibase (встроен в Jmix).
- `id` инцидента — человекочитаемый `INC-NNN`, последовательный, генерируется
  сервером; конкурентные создания не должны пропускать номера или давать дубли (Q-02, BT-34).
- В шаблоне уже есть security-сущность `inc_User` (UUID, username/password,
  `JmixUserDetails`) и changelog `010-init-user.xml` с пользователем `admin`.
  Доменный пользователь MVP — `id int, name, role (engineer|manager)` (FR-8, Q-01).
- Удаление инцидентов не предусмотрено; история неизменяема (AC-HIS-4).
- Толкование приоритета источников (Q-12): приоритет концепции распространяется на
  бизнес-правила; стек, схема запуска и тестовая БД — по `TASK.md`
  (PostgreSQL 16 + Liquibase + HSQLDB в тестах). Зафиксировано здесь как общее
  положение для всех ADR.

## Решение

### 1. Доменный пользователь — отдельная сущность `AppUser` (Q-01, вариант A)

`inc_User` **не расширяется** и остаётся технической сущностью Jmix Security
(аутентификация UI под `admin`, см. ADR-003). Доменный список пользователей —
новая сущность:

```
AppUser  @JmixEntity @Entity(name = "inc_AppUser") @Table(name = "INC_APP_USER")
  id    Integer  @Id (assigned, без генерации — id задаётся в seed-changelog)
  name  String   (обязательное, @InstanceName)
  role  UserRole (enum, обязательное)
  version Integer @Version
```

- Enum `UserRole implements EnumClass<String>` (`io.jmix.core.metamodel.datatype.EnumClass`):
  id `engineer`, `manager` + `static fromId(String)`. В БД хранится строковый id
  (подтверждено docs Jmix «Enumerations»: хранится id, не ordinal/name).
- Роль `engineer|manager` — атрибут домена для отображения; **никаких Spring Security
  ролей/прав** из неё не выводится (MVP без разграничения прав).
- Таблица `INC_APP_USER`: `ID integer PK`, `NAME varchar(255) not null`,
  `ROLE varchar(32) not null`, `VERSION integer not null`.
- Пользователи создаются **всегда** changelog'ом (не только при `SEED_DEMO`):
  FR-6 — список предзагружен, `GET /api/users`, селектор UI и валидация
  `X-User-Id`/`assignee_id` работают без demo-seed (AC-USERS-2 — единый источник).
  Состав (Q-14): id 1 Иванов И.И. (engineer), 2 Петров П.П. (engineer),
  3 Сидоров А.А. (engineer), 4 Кузнецова М.М. (manager).

### 2. Incident — строковый PK `INC-NNN`, assigned

```
Incident  @JmixEntity @Entity(name = "inc_Incident") @Table(name = "INC_INCIDENT")
  id          String  @Id (assigned; формат INC-%03d, без верхнего предела: INC-1000 допустим)
  title       String  (5–120, not null)
  description String  (0–2000, nullable, @Lob не нужен — varchar(2000))
  category    IncidentCategory (enum, not null)
  priority    IncidentPriority (enum, not null)
  status      IncidentStatus   (enum, not null; default New на уровне сервиса)
  assignee    AppUser  @ManyToOne(fetch = LAZY) @JoinColumn(name = "ASSIGNEE_ID") (nullable)
  resolution  String  (0–2000, nullable; write-once при resolve, ADR-002/Q-08)
  createdAt   OffsetDateTime (not null, серверное UTC)
  updatedAt   OffsetDateTime (not null, серверное UTC)
  version     Integer @Version
  comments    List<Comment>      @Composition (владение; UI-контейнеры)
  history     List<HistoryEvent> @Composition
```

- `@JmixGeneratedValue` **не используется** для `id`: аннотация поддерживает только
  Long/Integer/UUID, не String (проверено по docs «Entity ID generation»).
  Id присваивает `IncidentService` при создании (ADR-005).
- Enum'ы `IncidentCategory` (Network, Mail, Portal, Database, Workplace, Other),
  `IncidentPriority` (Critical, High, Medium, Low), `IncidentStatus`
  (New, In Progress, Resolved, Closed) — `EnumClass<String>`, **id совпадают с
  REST-значениями** (включая `"In Progress"` — пробел в id допустим, это строка).
  Это убирает маппинг enum↔JSON в REST-слое.
- `OffsetDateTime` — поддержанный тип атрибута Jmix out-of-the-box (docs
  «Entity attributes»); колонки `TIMESTAMP WITH TIME ZONE` (совместимо PG 16 и HSQLDB).
  Все значения пишет сервер в UTC.
- Таблица `INC_INCIDENT`: `ID varchar(20) PK`, `TITLE varchar(120) not null`,
  `DESCRIPTION varchar(2000)`, `CATEGORY varchar(32) not null`,
  `PRIORITY varchar(32) not null`, `STATUS varchar(32) not null`,
  `ASSIGNEE_ID integer` (FK → INC_APP_USER.ID), `RESOLUTION varchar(2000)`,
  `CREATED_AT timestamp with time zone not null`,
  `UPDATED_AT timestamp with time zone not null`, `VERSION integer not null`.
- Индексы: `IDX_INC_INCIDENT_ON_STATUS`, `IDX_INC_INCIDENT_ON_PRIORITY`,
  `IDX_INC_INCIDENT_ON_CREATED_AT` (фильтры реестра и сортировка `created_at desc`).
- Оптимистическая блокировка `@Version` — конкурентные PATCH не теряют обновления
  (паттерн шаблонного `inc_User`).

### 3. Генерация номера INC-NNN — таблица-счётчик под пессимистической блокировкой

Таблица `INC_ID_COUNTER`: `NAME varchar(64) PK`, `NEXT_VALUE bigint not null`;
одна строка `('incident', 1)`.

Алгоритм в `IncidentService.create(...)` (одна транзакция `@Transactional`):

1. `EntityManager.find(IncidentCounter.class, "incident", LockModeType.PESSIMISTIC_WRITE)`
   — блокировка строки счётчика (`SELECT … FOR UPDATE`; поддерживается PG и HSQLDB).
2. `id = String.format("INC-%03d", nextValue)`; `nextValue++`; сохранить счётчик.
3. Сохранить инцидент и событие истории `created` в той же транзакции.

`IncidentCounter` — техническая Jmix-сущность (`inc_IncidentCounter`, id String assigned,
nextValue Long), используется только через `EntityManager`
(`@PersistenceContext` в сервисе; docs «EntityManager» подтверждает нативный/JPA
доступ в Spring-бинах при открытой транзакции; события сущностей при этом не
рассылаются — для счётчика это не нужно).

Свойства:

- **Строгая беспробельность**: откат транзакции откатывает и счётчик — номера не
  «сгорают» при ошибках валидации/конфликтах; конкурентные создания сериализуются
  на строке счётчика (BT-34).
- Счётчик и инциденты атомарны: после seed/восстановления дампа номер всегда
  согласован с данными.
- `EntityManager` в UI-контроллерах не используется — только в сервисе (ADR-005).

### 4. Comment и HistoryEvent — Integer IDENTITY

```
Comment  @Entity(name = "inc_Comment") @Table(name = "INC_COMMENT")
  id        Integer @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  incident  Incident @ManyToOne(fetch = LAZY, optional = false)
            @JoinColumn(name = "INCIDENT_ID", nullable = false)
            @OnDeleteInverse(DeletePolicy.CASCADE)
  author    AppUser  @ManyToOne(fetch = LAZY, optional = false)
            @JoinColumn(name = "AUTHOR_ID", nullable = false)
  text      String (1–2000, not null)
  createdAt OffsetDateTime (not null)
  version   Integer @Version

HistoryEvent  @Entity(name = "inc_HistoryEvent") @Table(name = "INC_HISTORY_EVENT")
  id        Integer @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  incident  Incident @ManyToOne(fetch = LAZY, optional = false)
            @JoinColumn(name = "INCIDENT_ID", nullable = false)
            @OnDeleteInverse(DeletePolicy.CASCADE)
  actor     AppUser  @ManyToOne(fetch = LAZY, optional = false)
            @JoinColumn(name = "ACTOR_ID", nullable = false)
  type      HistoryEventType (enum, not null)
  payload   String (@Lob, TEXT/CLOB; JSON-строка «было/стало», пишет сервис)
  createdAt OffsetDateTime (not null)
  version   Integer @Version
```

- `GenerationType.IDENTITY` для Integer подтверждён docs Jmix (entity designer:
  «Identity column», suitable for Integer/Long); Liquibase `autoIncrement="true"`
  (PG и HSQLDB).
- `HistoryEventType implements EnumClass<String>`: id `created`, `assigned`,
  `status_changed`, `priority_changed`, `commented`, `resolved`, `fields_updated`
  (совпадают с REST-значениями).
- **payload — JSON-строка в TEXT/CLOB, не `jsonb`**: HSQLDB не имеет jsonb;
  сериализация Jackson `ObjectMapper` в сервисе (ADR-005), в REST отдаётся как
  JSON-объект (десериализация в DTO-маппере). Запросы внутрь payload не нужны —
  jsonb не даёт выигрыша в MVP.
- `@OnDeleteInverse(DeletePolicy.CASCADE)` + `@Composition` у владельца —
  семантика владения Jmix (docs «Compositions»); фактических удалений в MVP нет,
  FK без `ON DELETE CASCADE` (удаление запрещено, БД защищает ссылочную целостность).
- Таблицы:
  - `INC_COMMENT`: `ID integer PK autoIncrement`, `INCIDENT_ID varchar(20) not null`
    (FK → INC_INCIDENT.ID), `AUTHOR_ID integer not null` (FK → INC_APP_USER.ID),
    `TEXT varchar(2000) not null`, `CREATED_AT timestamp with time zone not null`,
    `VERSION integer not null`; индекс `IDX_INC_COMMENT_ON_INCIDENT_ID`.
    Колонка `TEXT` — допустимое имя в PG/HSQLDB; если backend при генерации
    changelog сочтёт имя рискованным, допустимо `TEXT_`/`BODY` с сохранением
    REST-поля `text` (решение о физическом имени — за backend по skill
    `jmix-create-liquibase-changelog`, контракт не меняется).
  - `INC_HISTORY_EVENT`: `ID integer PK autoIncrement`, `INCIDENT_ID varchar(20) not null`
    (FK), `ACTOR_ID integer not null` (FK), `TYPE varchar(32) not null`,
    `PAYLOAD text/clob`, `CREATED_AT timestamp with time zone not null`,
    `VERSION integer not null`; индексы
    `IDX_INC_HISTORY_ON_INCIDENT_CREATED (INCIDENT_ID, CREATED_AT)` (таб истории)
    и `IDX_INC_HISTORY_ON_CREATED_AT` (recent_activity, resolved_today, динамика).

### 5. Liquibase changelog'и

Каталог `src/main/resources/com/company/incidentjmix/liquibase/changelog/`
(master `changelog.xml` уже содержит `includeAll` — новые файлы подхватываются
автоматически). Паттерн — как в шаблонном `010-init-user.xml`:
`author="incident-jmix"`, `context="!cuba"`, `logicalFilePath` по образцу шаблона.

| Файл | Содержимое |
|---|---|
| `020-init-app-user.xml` | `INC_APP_USER` + insert 4 пользователей (Q-14). Values простые (int/varchar) — один changeSet без dbms-ветвления, работает на PG и HSQLDB. Пользователи создаются **всегда**, не зависят от `SEED_DEMO`. |
| `030-incident.xml` | `INC_INCIDENT` + индексы status/priority/created_at + FK assignee. |
| `040-comment-history.xml` | `INC_COMMENT`, `INC_HISTORY_EVENT` + индексы + FK. |
| `050-id-counter.xml` | `INC_ID_COUNTER` + insert `('incident', 1)`. |

Точные имена changeSet id, dbms-атрибуты и типы — за backend по skill
`jmix-create-liquibase-changelog` (схема выше — контракт, которому changelog
обязан соответствовать). Шаблонные `INC_USER`/security-таблицы не трогаем.

## Альтернативы

1. **Расширить `inc_User` доменными полями (reuse).** Отклонено: смешение
   security-модели (UUID, password, JmixUserDetails) с доменным списком (int id,
   name, role); `assignee_id`/`author_id`/`actor_id` в контракте — int, а не UUID;
   пришлось бы маппить UUID↔int во всех DTO. Отдельный `AppUser` дешевле и чище
   (рекомендация analyst Q-01 A).
2. **Jmix `Sequences` (`io.jmix.data.Sequences.createNextValue`) для номера.**
   Отклонено как основной механизм: docs Jmix явно предупреждают о **пропусках
   номеров** при откате транзакции/ошибке (гарантируется только уникальность).
   Требование Q-02/BT-34 — последовательность без пропусков. Допустимо как
   запасной вариант, если пессимистическая блокировка окажется проблемной на
   HSQLDB (тогда требование беспробельности ослабляется только для rollback —
   зафиксировать отдельно).
3. **`max(id)+1` при создании.** Отклонено: гонка при конкурентных созданиях
   (BT-34), прямо запрещено рекомендацией Q-02.
4. **Суррогатный UUID-PK + отдельный номер.** Отклонено: лишний маппинг во всех
   эндпоинтах (`/api/incidents/{id}` принимает `INC-NNN`), номер всё равно требует
   генерации под блокировкой; строковый PK рекомендован Q-02.
5. **`jsonb` для payload.** Отклонено: несовместим с HSQLDB в тестах; запросы по
   содержимому payload не требуются.
6. **Колонка `resolved_at` для resolved_today.** Отклонено (Q-04 a): событие
   `resolved` в истории уже фиксирует момент перехода; отдельная колонка
   дублирует данные и требует синхронизации. Индекс по `CREATED_AT` истории
   покрывает запрос.

## Последствия

- Backend создаёт 5 сущностей (`AppUser`, `Incident`, `Comment`, `HistoryEvent`,
  `IncidentCounter`), 5 enum'ов (`UserRole`, `IncidentCategory`, `IncidentPriority`,
  `IncidentStatus`, `HistoryEventType`) и 4 changelog'а; каждый артефакт — по
  профильному skill (`jmix-create-entity`, `jmix-create-enum`,
  `jmix-create-liquibase-changelog`).
- REST- и UI-слои работают с одними и теми же сущностями; `assignee_id` в JSON =
  `AppUser.id` (int), в сущности — ссылка `assignee`.
- Все бизнес-инварианты (длины, обязательность, автомат) проверяются в сервисе
  (ADR-005), а не ограничениями БД; БД гарантирует только not null/FK/уникальность PK.
- Счётчик — единственная точка сериализации записи; при массовом импорте это
  узкое место, для MVP несущественно.
- `@Version` на Incident означает: UI и REST должны перечитывать сущность в
  транзакции операции (сервисы это делают); «висячие» формы получают конфликт
  оптимистической блокировки — обработать как 409 (ADR-002).
- Тестовая HSQLDB: `IDENTITY`, `TIMESTAMP WITH TIME ZONE`, `FOR UPDATE` —
  поддерживаются; backend проверяет сходимость схемы при `./gradlew test`
  (gate 2, skill `jmix-verify-bootrun`).
