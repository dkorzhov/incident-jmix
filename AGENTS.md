# AGENTS.md — IT Incident Tracker (MVP, Jmix)

**Источник истины по требованиям — `TASK.md`** (оригинал концепции — в `docs/`, макет UI — `docs/ui-concept.png`).
Не расширяй скоуп: раздел 8 TASK.md обязателен к соблюдению. SLA в проекте НЕТ.
Это Jmix-версия проекта `incident` (FastAPI + React): требования идентичны, стек другой.

## Стек (зафиксирован для этого проекта)

- **Jmix 3.1** (Spring Boot 4, Vaadin 25 Flow UI, EclipseLink), **Java 21** (Temurin), сборка **Gradle**.
- БД: **PostgreSQL 16** (docker compose), миграции **Liquibase** (встроены в Jmix, применяются при старте).
- UI: **Jmix Flow UI** (Java + XML-дескрипторы), отдельного frontend-приложения нет.
- Графики: **Charts add-on** (`io.jmix.charts:jmix-charts-flowui-starter`, Apache ECharts) — уже подключён.
- REST: кастомные Spring `@RestController` (`/api/**`, контракт TASK.md §3), без аутентификации, `X-User-Id`.
- Тесты: **JUnit 5** + Jmix integration/UI tests (БД в тестах — HSQLDB), **Playwright** (e2e/).

## Окружение

- JDK: `~/tools/jdk-21.0.13+9` (`JAVA_HOME` прописан в `~/.bashrc`). Jmix CLI: `~/.local/bin/jmix`.
- Gradle-кэш живёт в `~/.gradle` (вне worktree) — root-owned кэшей, как в Docker-проектах, здесь нет.
- Тестовая HSQLDB пишет в `.jmix/` (в .gitignore). Не коммитить `.jmix`, `.gradle`, `build/`, `node_modules/`.

## Команды

| Команда | Что делает |
|---|---|
| `docker compose up -d db` | поднять PostgreSQL 16 |
| `./gradlew bootRun` | запустить приложение (UI + REST) на :8080 |
| `./gradlew test` | backend/UI-тесты (HSQLDB, Postgres не нужен) |
| `./gradlew build` | полная сборка + тесты |
| `cd e2e && npm ci && npx playwright test` | E2E против запущенного приложения |

Перед завершением задачи: `./gradlew build` зелёный локально и в CI.

## Роли агентов

| Агент | Роль | Права |
|---|---|---|
| `analyst` | требования, user stories, критерии приёмки | read-only, пишет только в `docs/` |
| `architect` | архитектура, API-контракт, схема БД, ADR | read-only, пишет только в `docs/` |
| `backend` | сущности, сервисы, бизнес-логика, REST, Liquibase | правит `src/main/java` (кроме `view/`), `src/main/resources/**/liquibase`, не трогает UI |
| `frontend` | Flow UI: view-классы, XML-дескрипторы, темы, меню | правит `view/`, `themes/`, `menu.xml`, messages, не трогает домен/REST |
| `qa` | тест-кейсы, автотесты, поиск дефектов | правит только тесты (`src/test/`, `e2e/`) |

Конвейер: analyst → architect → backend → frontend → qa. Каждый этап — отдельная сессия/worker
со своим агентом; результаты предыдущего этапа читаются из `docs/` и кода.

## Правила работы

1. **Одна задача Linear = одна ветка = один PR.** Ветка: `feat/DMI-<номер>-<slug>`.
2. Прямые пуши в `main` запрещены. PR через `gh pr create`, мержит человек.
3. Коммиты — conventional commits (`feat:`, `fix:`, `test:`, `chore:`, `docs:`).
4. Бизнес-правила проверяются на backend, не только в UI (TASK.md §2). Closed неизменяем, удаления нет.
5. Каждое значимое изменение — автоматическая запись в историю; payload «было/стало».
6. Не удаляй и не ослабляй существующие тесты ради «зелёного» прогона.
7. Текущий пользователь для REST — заголовок `X-User-Id`, валидируется против списка users.
8. Секреты и `.env` не коммитить.
9. **Перед изменением любого файла прочитай skill `jmix`** (`.agents/skills/`) и следуй ему:
   он маршрутизирует задачу по артефактам (entity, view, service, test…) и задаёт проверки.
   Документация Jmix — через MCP `jmix-docs` (remote, поднимать свой сервер не нужно).
10. Stacked PR: мержим базу без squash либо сразу `rebase --onto origin/main <head-базы-до-мержа>`
    + `push --force-with-lease`; в описании PR указываем базу и порядок мержа.

## Структура

```
src/main/java/com/company/incidentjmix/
            entity/  service/  rest/  security/  view/
src/main/resources/  application.properties, liquibase/, menu.xml, themes/, view-XML
src/test/            JUnit 5 + Jmix test support
e2e/                 Playwright-сценарии
docs/                концепция (docx), макет (ui-concept.png), ADR и спеки агентов
.agents/skills/      Jmix Agent Toolkit skills
TASK.md              рабочая спецификация
```

## Linear

Команда `DMI`, проект Incident Tracker Jmix. В описании PR указывай DMI-NNN.

<!-- BEGIN jmix-agent-toolkit -->
## Jmix

This is a Jmix 3 application. Before writing or changing ANY file in it, read
the `jmix` skill and follow it. It maps the task to artifacts, routes each
artifact to the skill that governs it, and names the checks that close a task.
Your Jmix/Vaadin priors are not reliable here; the skills are.

Managed by the Jmix Agent Toolkit. Content between these markers is replaced on
re-install — put your own instructions outside them.
<!-- END jmix-agent-toolkit -->
