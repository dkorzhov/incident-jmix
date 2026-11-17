# IT Incident Tracker — Jmix (MVP)

Jmix-версия проекта [`incident`](https://github.com/dkorzhov/incident): те же требования
(`TASK.md`, концепция в `docs/`), другой стек. Эксперимент по агентной разработке.

## Стек

- **Jmix 3.1** (Spring Boot 4, Vaadin 25 Flow UI, EclipseLink), Java 21, Gradle
- **PostgreSQL 16** (docker compose), миграции **Liquibase** (применяются при старте)
- **Charts add-on** (`io.jmix.charts:jmix-charts-flowui-starter`, Apache ECharts) — для dashboard
- REST API — кастомные `@RestController` (`/api/**`, контракт TASK.md §3)
- Тесты: JUnit 5 + Jmix test support (HSQLDB), Playwright (`e2e/`)

## Требования

- JDK 21 или 25 (`JAVA_HOME`). На этой машине: `~/tools/jdk-21.0.13+9` (прописан в `~/.bashrc`).
- Docker (для PostgreSQL), Node.js (для e2e).
- Jmix CLI (`~/.local/bin/jmix`) — для создания/обновления проектов, не обязателен для сборки.

## Запуск

```sh
docker compose up -d db        # PostgreSQL 16 на :5434 (incident/incident/incident)
./gradlew bootRun              # приложение на http://localhost:8080
```

UI: логин шаблона `admin` / `admin` (MVP-режим без аутентификации и селектор
пользователя будут реализованы по TASK.md, решение фиксируется в ADR).
Переопределение БД: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.

## Миграции

Liquibase changelog: `src/main/resources/com/company/incidentjmix/liquibase/changelog.xml`.
Применяется автоматически при старте приложения.

## Demo-данные

План (TASK.md §6): `SEED_DEMO=true` — пользователи и инциденты в разных статусах
(включая Critical и решённый сегодня). Свойство-заготовка: `app.seed-demo` в
`application.properties`; реализация — отдельная задача.

## Тесты

```sh
./gradlew test                 # JUnit 5 + Jmix UI tests, БД HSQLDB (Postgres не нужен)
./gradlew build                # полная сборка + тесты
cd e2e && npm ci && npx playwright test   # E2E против запущенного приложения
```

## Агентная разработка

- `AGENTS.md` — правила и роли; `opencode.json` — агенты (analyst/architect/backend/frontend/qa) и MCP.
- **Jmix Agent Toolkit**: skills в `.agents/skills/` (читать skill `jmix` перед изменением файлов).
- **MCP `jmix-docs`** — удалённый сервер `https://docs-mcp.jmix.io/mcp` (поднимать свой не нужно),
  поиск по документации Jmix 3. **MCP playwright** — для e2e-отладки.
- Linear: команда `DMI`, проект Incident Tracker Jmix; одна задача = ветка `feat/DMI-N-slug` = PR.

## Структура

```
src/main/java/com/company/incidentjmix/   entity, security, view (шаблон)
src/main/resources/                       application.properties, liquibase, menu, themes
src/test/                                 тесты шаблона (User)
e2e/                                      Playwright
docs/                                     концепция (docx), макет UI, ADR
```
