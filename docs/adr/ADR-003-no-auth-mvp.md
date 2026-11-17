# ADR-003: MVP без аутентификации — текущий пользователь в UI и REST

- **Статус:** Accepted
- **Дата:** 2026-11-17
- **Тикет:** DMI-15
- **Связанные документы:** `TASK.md` §1 (User), §3, §8; `docs/requirements.md` (FR-7, FR-8, AC-HEADER-1…3, AC-HIS-3), `docs/questions.md` (Q-01, Q-06, Q-13); ADR-001 (AppUser), ADR-002 (X-User-Id)

## Контекст

Аутентификация/роли/SSO вне MVP (§8). Но Jmix Flow UI построен поверх Spring
Security: view-доступ, DataManager и меню требуют аутентифицированного
пользователя с правами. При этом доменный «текущий пользователь» (для actor_id
в истории и author в комментариях) выбирается из списка `AppUser` — в UI
селектором, в REST заголовком `X-User-Id`. Нужно развести **техническую**
аутентификацию Jmix и **доменного** актёра.

## Решение

### 1. Два уровня идентичности

| Уровень | Кто | Механизм |
|---|---|---|
| Технический (Jmix Security) | `admin` из `inc_User` (шаблонный, `system-full-access`) | авто-аутентификация UI; для `/api/**` — anonymous + `SystemAuthenticator.withSystem` (ADR-002) |
| Доменный (бизнес) | `AppUser` (engineer/manager) | UI: селектор в шапке + `SessionData`; REST: `X-User-Id` |

Сервисы **не читают** технического пользователя для актёра: каждый mutating-метод
принимает `AppUser actor` явным параметром (ADR-005). `CurrentAuthentication`
используется только фреймворком. Роль `engineer|manager` прав не даёт и не
ограничивает — чисто доменный атрибут (Q-01).

### 2. UI: автоматический вход под admin

Шаблонный `LoginView` (`inc_LoginView`) сохраняется как маршрут, но в
`InitEvent` вместо показа формы выполняет автоматическую аутентификацию:

```java
loginViewSupport.authenticate(
    AuthDetails.of(defaultUsername, defaultPassword)   // ui.login.default* = admin/admin
        .withLocale(messages.getLocale()));            // ru по умолчанию для MVP
```

Проверенные символы (шаблонный `LoginView` + docs «LoginForm»):
`io.jmix.securityflowui.authentication.LoginViewSupport.authenticate(AuthDetails)`,
`io.jmix.securityflowui.authentication.AuthDetails.of(...)` — authenticate сам
выполняет аутентификацию через `AuthenticationManager` и навигацию в main view.

- Пользователь, открывший приложение, сразу попадает в `MainView`; форма логина
  фактически не показывается.
- Fallback: если авто-аутентификация бросила исключение (например, admin
  удалён/деактивирован), показать шаблонную форму логина с ошибкой — не «белый
  экран». Шаблонные обработчики `BadCredentialsException` и т.п. сохраняются.
- Вызов `showWarningIfDefaultCredentials()` из шаблона **убирается**: для MVP
  admin/admin — осознанный технический аккаунт локального демо, предупреждение
  о смене пароля нерелевантно (свойство `ui.login.defaultUsername/Password`
  остаётся в `application.properties`).
- `jmix.ui.login-view-id=inc_LoginView` и UI security chain не меняются;
  `admin` продолжает создаваться changelog'ом `010-init-user.xml`
  (`{noop}admin`, роль `system-full-access`). Это демо-аккаунт шаблона, не
  секрет; реальные секреты в репозиторий не попадают (правило 8 AGENTS.md).

### 3. UI: селектор текущего пользователя в шапке MainView

- В `main-view.xml` в шапке (зона шаблонного `userMenu`) размещается
  `comboBox` со списком `AppUser` (item label = name, можно с ролью).
  Шаблонный `userMenu` (аватар/имя admin) **заменяется** селектором: показывать
  технического admin пользователю не нужно. `CurrentUserSubstitution` в MVP
  **не используется** (см. альтернативы).
- Список — из `AppUserService.list()` (тот же источник, что `GET /api/users`,
  AC-HEADER-1/AC-USERS-2).
- Выбор хранится в **`io.jmix.core.session.SessionData`** (подтверждён javadoc;
  штатный механизм Jmix для атрибутов пользовательской сессии, инжектится
  прямо в UI-контроллеры): атрибут `currentAppUserId` (Integer).
- Default при первом открытии — **первый пользователь по id** (Q-13);
  селектор отображает его, значение сразу кладётся в SessionData.
- Смена значения → `sessionData.setAttribute("currentAppUserId", id)`; все
  последующие UI-операции используют нового актёра (AC-HEADER-2).
- Чтение актёра — helper (например, метод `CurrentAppUserProvider` в `view/`
  или `service/`): берёт id из SessionData, загружает `AppUser` через
  `AppUserService`; если атрибут пуст (новая сессия) — default. Каждый вызов
  сервиса получает свежий `AppUser`, а не закэшированную сущность.
- Сессия Vaadin изолирует выбор между браузерами/вкладками одного пользователя
  ОС; перезапуск браузера сбрасывает выбор на default — приемлемо для MVP.

### 4. REST: X-User-Id

Как зафиксировано в ADR-002 §3: заголовок валидируется против `AppUserService`;
mutating — обязателен (401 при отсутствии/неизвестном), GET — опционален
(неизвестное значение → 401). Техническая аутентификация `/api/**` — anonymous
permitAll + `SystemAuthenticator.withSystem` для доступа к данным.

### 5. Судьба шаблонных артефактов

- `inc_User`, `DatabaseUserRepository`, `FullAccessRole`, `UiMinimalRole`,
  `010-init-user.xml` — **остаются без изменений** (техническая инфраструктура).
- Шаблонный `UserListView` (`inc_User.list`, маршрут `users`) **заменяется** на
  read-only список доменных `AppUser` (ADR-004); маршрут `users` переходит
  новому view, пункт меню `inc_User.list` убирается.
- Тесты: шаблонный `AuthenticatedAsAdmin` продолжает работать (UI-тесты
  аутентифицируются как admin); доменный актёр в UI-тестах задаётся через
  SessionData (UT-06).

## Альтернативы

1. **Полностью отключить security Jmix.** Отклонено: Flow UI/DataManager
   требуют аутентификации; отключение ломает фреймворк и противоречит стеку.
2. **`CurrentUserSubstitution` (подмена пользователя в сессии).** Отклонено:
   механизм требует существования подменяемого пользователя в `inc_User` и
   управления его правами; пришлось бы синхронизировать AppUser↔inc_User и
   решать вопрос паролей/ролей. Явный параметр `actor` проще, тестируемее и не
   трогает security-модель. Может быть пересмотрено при переходе к реальной
   аутентификации.
3. **Анонимный UI с ролью FullAccess.** Отклонено: anonymous-доступ к UI
   обходит login-view механику Jmix неочевидным образом; авто-вход под admin
   использует штатный `LoginViewSupport` и предсказуем.
4. **Расширить `inc_User` и использовать его как доменный список.** Отклонено
   в ADR-001 (UUID vs int, security-поля).
5. **Хранить выбор в cookie/localStorage вместо сессии.** Отклонено: SessionData
   — штатный серверный механизм Jmix, не требует клиентского кода и доступен в
   UI-тестах.

## Последствия

- Все UI-действия выполняются с полными правами admin; единственность защиты
  бизнес-правил — сервисный слой (ADR-005), что и требует NFR-7/BR. Для MVP
  (локальное демо, §8) приемлемо; при появлении реальной аутентификации права
  переносятся в resource roles без изменения сервисов (актёр уже параметр).
- Актёр в истории всегда доменный: UI — выбранный в селекторе, REST — X-User-Id;
  технический admin в истории/комментариях не фигурирует (AC-HIS-3).
- Frontend: правит `LoginView` (авто-вход), `MainView`/`main-view.xml`
  (селектор), убирает `userMenu`-рендереры; новые символы (comboBox и пр.)
  проверяет по skill `jmix-verify-api-symbol` и профильным UI-skills.
- QA: UT-06 (смена селектора меняет актёра), 401-матрица REST (BT), авто-вход
  (открытие `/` ведёт в MainView без формы логина) — включить в E2E-01.
- Риск: утеря/смена пароля admin блокирует авто-вход; fallback-форма логина
  сохранена, восстановление — пересоздание локальной БД (демо-окружение).
