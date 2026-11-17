# ADR-004: Flow UI — views, меню, Charts

- **Статус:** Accepted
- **Дата:** 2026-11-17
- **Тикет:** DMI-15
- **Связанные документы:** `TASK.md` §4, `docs/requirements.md` (UI-0…UI-8, AC-HEADER), `docs/questions.md` (Q-11, Q-13, Q-15), `docs/ui-concept.png`; ADR-001 (сущности), ADR-003 (селектор пользователя), ADR-005 (сервисы)

## Контекст

UI — Jmix Flow UI (Java + XML), визуальный ориентир `docs/ui-concept.png`:
тёмный сайдбар, KPI-карточки, бейджи статусов/приоритетов, карточка с табами,
dashboard с графиками. Отдельного frontend-приложения нет. Charts add-on
(`io.jmix.charts:jmix-charts-flowui-starter`) уже подключён в `build.gradle`.
Все бизнес-правила — в сервисах (NFR-7); UI не дублирует их, а только
предотвращает заведомо недопустимые действия (показывает лишь допустимые
переходы) и отображает ошибки сервера.

## Решение

### 1. Каталог views

Пакет `com.company.incidentjmix.view.*`, id с префиксом `inc_` (паттерн шаблона).

| View | Id / маршрут | Базовый класс | Назначение |
|---|---|---|---|
| `view/dashboard/DashboardView` | `inc_DashboardView` / `dashboard` | `StandardView` | KPI, графики, недавняя активность |
| `view/incident/IncidentListView` | `inc_Incident.list` / `incidents` | `StandardListView<Incident>` | реестр |
| `view/incident/IncidentDetailView` | `inc_Incident.detail` / `incidents/:id` | `StandardDetailView<Incident>` | карточка (табы) + режим создания в диалоге |
| `view/user/AppUserListView` | `inc_AppUser.list` / `users` | `StandardListView<AppUser>` | read-only список пользователей |

- `MainView`/`LoginView` — шаблонные, модифицируются по ADR-003 (авто-вход,
  селектор в шапке).
- После логина открывается Dashboard: добавить в `application.properties`
  **`jmix.ui.default-view-id=inc_DashboardView`** (свойство подтверждено docs
  «UI Properties»: view, открываемый после логина внутри main view).
- Шаблонный `UserListView` (`inc_User.list`) и его дескриптор удаляются
  (заменяются `AppUserListView`); шаблонные тесты `UserUiTest`/`UserTest`
  QA адаптирует (не ослабляя покрытие security-сущности — `inc_User` остаётся).
- Маршрут карточки — `incidents/:id` со строковым параметром `INC-NNN`
  (`StandardDetailView.initExistingEntity(String)` — подтверждён javadoc;
  паттерн base route + `/:id` — docs «View Templates»).

### 2. Принципы работы с данными (важно для frontend)

- **Все мутации — только через сервисы** (`IncidentService`, `CommentService`,
  ADR-005) с явным актёром из селектора (ADR-003). Стандартные save-действия
  `StandardDetailView`, коммитящие DataContext напрямую в DataManager,
  **не используются для персистентности**: они обошли бы бизнес-правила и
  записали бы историю без доменного актёра. DataContext/контейнеры применяются
  для загрузки и привязки полей; кнопки «Сохранить», «Создать», переходы
  статуса, «Отправить комментарий» вызывают сервисы, после успеха — перезагрузка
  контейнеров. Frontend читает skill `jmix-create-detail-view` и реализует
  кастомные действия в рамках правил фреймворка (отклонение от стандартного
  save-close паттерна — осознанное, причина: NFR-7 + AC-HIS-3).
- Ошибки сервисов (доменные исключения с текстом «поле: причина») показываются
  `Notifications` (`io.jmix.flowui.Notifications`, подтверждён шаблоном):
  `notifications.create(detail).withType(ERROR)…show()` — не молча (UI-6,
  AC-ERR-1). REST- и UI-тексты ошибок совпадают (один сервис).
- Клиентская валидация: длина title 5–120 на форме создания (UI-3), обязательный
  resolution в форме resolve; серверная проверка остаётся источником истины.

### 3. Реестр (`IncidentListView`)

- KPI-карточки (открытые, новые, в работе, критические, решено сегодня) — из
  `DashboardService` (те же агрегаты, что REST).
- Поиск `textField`/`searchField` по ID и title (регистронезависимая подстрока),
  фильтры `comboBox` статус/приоритет (со значениями «все»); условия
  подставляются в JPQL загрузчика контейнера (или перезагрузка через
  `DataManager` с условиями — выбирает frontend по skill `jmix-create-list-view`).
- `dataGrid` колонки: ID, название, приоритет (бейдж), статус (бейдж),
  ответственный (name из `AppUser`); сортировка `created_at desc, id desc`;
  **без пагинации** (компонент pagination не добавляется; объём MVP мал).
- Клик по строке → карточка `incidents/{id}`; кнопка «Новый инцидент» →
  диалог создания (см. §4). Пустой результат — пустое состояние с подсказкой
  (UI-6), не пустая таблица молча.
- Бейджи — рендереры колонок с CSS-классами; цвета по UI-7: Critical красный,
  High оранжевый, Medium жёлтый, Low синий; New синий, In Progress янтарный,
  Resolved зелёный, Closed серый. Конкретные токены темы (`--aura-*`/`--lumo-*`)
  frontend **проверяет по skill `jmix-style-ui`** (неопределённый токен
  fails silently); классы бейджей определяются в теме `incident-jmix-aura`.

### 4. Карточка (`IncidentDetailView`)

- Шапка: `INC-NNN` + title, бейджи статуса/приоритета.
- **`tabSheet`** (подтверждён docs «TabSheet»; XML `<tabSheet><tab …>`, каждый
  tab оборачивает содержимое в `vbox`; Java-класс
  `io.jmix.flowui.component.tabsheet.JmixTabSheet`):
  - **Информация**: поля (title, description, category, priority, assignee,
    resolution read-only, created_at/updated_at); редактирование допустимых
    полей + «Сохранить» → `IncidentService.update`; смена assignee — comboBox
    списка `AppUser` (в т.ч. «не назначен»);
  - **История**: `dataGrid` событий (новые сверху): время (UTC), актёр (name),
    тип, payload «было/стало» человекочитаемо;
  - **Комментарии**: лента (хронологически) + поле ввода + «Отправить» →
    `CommentService.add`.
- Действия автомата — **только допустимые для текущего статуса**:
  New → «Взять в работу»; In Progress → «Решить» (открывает форму/диалог с
  обязательным `resolution` ≤2000, submit → `IncidentService.changeStatus`);
  Resolved → «Закрыть»; Closed → действий нет, все поля read-only
  (`setReadOnly(true)` — подтверждён javadoc `StandardDetailView`).
- **Создание** — тот же detail view в CREATE-режиме, открывается диалогом из
  реестра (проверенный паттерн docs «Opening Views»):

  ```java
  dialogWindows.detail(this, Incident.class)
      .withViewClass(IncidentDetailView.class)
      .newEntity()
      .withAfterCloseListener(e -> { /* после успешного создания — переход в карточку */ })
      .open();
  ```

  (`io.jmix.flowui.DialogWindows`, `DetailWindowBuilder.newEntity()` —
  подтверждены javadoc/docs.) В режиме создания табы История/Комментарии
  скрыты; кнопка «Создать» вызывает `IncidentService.create(actor, …)` и
  закрывает диалог с outcome SAVE; листенер реестра обновляет список и
  navigates на `incidents/{новый id}` (точную навигацию frontend проверяет
  по docs/skill). Прямой маршрут создания не вводится — одна точка входа.

### 5. Dashboard (`DashboardView`)

- KPI-карточки: `counts` из `DashboardService` (open, new, in_progress,
  critical, resolved_today).
- **Диаграмма по приоритетам** — `charts:chart`, series `pie`, данные
  `by_priority` (открытые инциденты).
- **График динамики** — `charts:chart`, series `line` (два ряда: создано /
  решено), ось X — даты последних 7 суток UTC (`dynamics`, ADR-002 §5).
- **Недавняя активность** — список последних 10 событий (`recent_activity`):
  инцидент (ссылка на карточку), актёр, тип, время UTC.
- Charts add-on (проверено docs «Charts» + demo samples):
  - XML namespace **`xmlns:charts="http://jmix.io/schema/charts/ui"`**,
    элемент `<charts:chart>` (атрибуты width/height, вложенные
    dataSet/series/title/legend/xAxes/yAxes/tooltip; `nativeJson` — для
    тонкой настройки ECharts при необходимости);
  - Java: **`io.jmix.chartsflowui.component.Chart`** (пакет `chartsflowui`
    слитно), **`io.jmix.chartsflowui.data.item.MapDataItem`**,
    **`io.jmix.chartsflowui.kit.component.model.DataSet`**,
    **`io.jmix.chartsflowui.kit.data.chart.ListChartItems`**;
  - паттерн привязки (demo map-data-item):

    ```java
    chart.withDataSet(new DataSet().withSource(
        new DataSet.Source<MapDataItem>()
            .withDataProvider(new ListChartItems<>(items))   // items: Map.of("priority","Critical","count",2)…
            .withCategoryField("priority")
            .withValueField("count")));
    ```

  - данные готовит `DashboardService` (те же DTO, что REST) → маппинг в
    `MapDataItem` в контроллере view; нулевые категории приоритетов UI
    дополняет сам (API отдаёт только ненулевые, ADR-002).
- Пустая БД: нулевые KPI, пустые графики с пустым состоянием, без ошибок
  (AC-DASH-6).

### 6. Пользователи (`AppUserListView`)

Read-only `dataGrid`: id, name, role (локализованные подписи engineer/manager).
Действий создания/редактирования/удаления нет (FR-6).

### 7. Меню, тема, локализация

- `menu.xml` (xmlns `http://jmix.io/schema/flowui/menu`), меню `application`:
  **Дашборд** (`inc_DashboardView`), **Инциденты** (`inc_Incident.list`),
  **Пользователи** (`inc_AppUser.list`). Шаблонный пункт `inc_User.list`
  убрать; «Настройки» из макета **не включать** (Q-11).
- Тёмный сайдбар — кастомизация темы `incident-jmix-aura` (CSS; токены
  проверять по skill `jmix-style-ui`); визуальный ориентир — `ui-concept.png`.
- Локализация: `jmix.core.available-locales=en,ru` (уже в шаблоне); UI по
  умолчанию **ru**, все подписи views/enum/действий — через message keys
  (skill `jmix-add-i18n-keys`), bundles ru + en.
- Время отображается в **UTC с явной пометкой «(UTC)»** (UI-8); формат
  `dd.MM.yyyy HH:mm`.

## Альтернативы

1. **Отдельный frontend (React) поверх REST.** Отклонено: стек зафиксирован —
   Flow UI, отдельного frontend-приложения нет (TASK.md §0).
2. **Стандартный save-close паттерн detail view + entity listeners для правил.**
   Отклонено: listener не знает доменного актёра (ADR-005), бизнес-правила
   оказались бы в двух местах; кастомные действия поверх сервисов — единая
   точка правил для UI и REST.
3. **Отдельный `IncidentCreateView`.** Отклонено: дублирование формы и логики
   валидации; CREATE-режим того же detail view в диалоге — штатный паттерн
   Jmix (`DialogWindows…newEntity()`).
4. **Графики вручную (Vaadin/ECharts JS).** Отклонено: Charts add-on уже
   подключён и покрывает pie/line (TASK.md §0).
5. **Пагинация реестра.** Вне MVP (§8); не добавляем.

## Последствия

- Frontend создаёт 4 view-класса + XML-дескрипторы, правит `MainView`,
  `LoginView`, `menu.xml`, `application.properties` (`default-view-id`), тему и
  messages; каждый артефакт — по профильному skill (`jmix-create-list-view`,
  `jmix-create-detail-view`, `jmix-create-fragment` при выделении фрагментов,
  `jmix-style-ui`, `jmix-add-i18n-keys`); символы, не проверенные в этом ADR
  (рендереры колонок, навигация, comboBox-бинарии), — по skill
  `jmix-verify-api-symbol` перед написанием.
- UI-интеграционные тесты (`jmix-flowui-test-assist`, UT-01…06) и E2E
  (Playwright, E2E-01) опираются на id view/компонентов из этого ADR;
  стабильные id компонентов заложить сразу.
- Удаление шаблонного `UserListView` требует адаптации шаблонных тестов —
  задача QA, покрытие `inc_User` (security) сохраняется.
- Карточка и реестр читают данные через контейнеры с fetch plan'ами,
  включающими `assignee` (и `author`/`actor` для лент) — избежать
  unfetched/N+1 (skill `jmix-configure-fetch-plan`).
