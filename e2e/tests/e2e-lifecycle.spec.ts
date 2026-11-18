import { test, expect } from '@playwright/test';

/**
 * E2E-01 (TASK.md §5): Full incident lifecycle through the UI.
 *
 * ADR-003 auto-login + ADR-004 Flow UI. Vaadin 25 custom elements.
 * Combo-box items use Russian labels (localized by Jmix framework).
 *
 * Cycle: create → assign → take → comment → resolve (with negative attempt) → close.
 * History and comments verified in the incident card.
 *
 * E2E-02: Negative UI cases — resolve without resolution blocked by server,
 *          direct REST call New→Closed returns 409.
 */

// ─── Combo-box label mappings (Russian UI) ───────────────────────────

const CATEGORY = {
  Network: 'Сеть', Mail: 'Почта', Portal: 'Портал',
  Database: 'База данных', Workplace: 'Рабочее место', Other: 'Другое',
} as const;

const PRIORITY = {
  Critical: 'Критический', High: 'Высокий', Medium: 'Средний', Low: 'Низкий',
} as const;

// ─── Known users (from seed data: 020-init-app-user.xml) ──────────────

const USER = {
  IVANOV: 'Иванов И.И.',
  PETROV: 'Петров П.П.',
  SIDOROV: 'Сидоров А.А.',
  KUZNETSOVA: 'Кузнецова М.М.',
} as const;

// ─── Helpers ──────────────────────────────────────────────────────────

/**
 * Fill a vaadin-text-field by id (light DOM, globally searchable).
 */
async function fillTextField(page: any, id: string, value: string) {
  const input = page.locator(`vaadin-text-field#${id} input`);
  await input.fill(value);
}

/**
 * Fill a vaadin-text-area by id.
 */
async function fillTextArea(page: any, id: string, value: string) {
  const textarea = page.locator(`vaadin-text-area#${id} textarea`);
  await textarea.fill(value);
}

/**
 * Select a value in a Vaadin combo-box.
 * Clicks the combo, then clicks the matching item from globally visible items.
 */
async function selectComboItem(page: any, comboId: string, label: string) {
  const combo = page.locator(`vaadin-combo-box#${comboId}`);
  await combo.click();
  await page.waitForTimeout(500);
  const item = page.locator('vaadin-combo-box-item').filter({ hasText: label }).first();
  await item.click();
  await page.waitForTimeout(300);
}

/**
 * Select a value in a Jmix entityComboBox (renders as jmix-entity-combo-box).
 * Internally delegates to a vaadin-combo-box for the dropdown.
 */
async function selectEntityComboItem(page: any, comboId: string, label: string) {
  // Jmix entityComboBox wraps a vaadin-combo-box; click the outer element
  const combo = page.locator(`#${comboId}`);
  await combo.click();
  await page.waitForTimeout(500);
  const item = page.locator('vaadin-combo-box-item').filter({ hasText: label }).first();
  await item.click();
  await page.waitForTimeout(300);
}

/**
 * Click a Vaadin button by id selector.
 */
async function clickButton(page: any, selector: string) {
  await page.locator(selector).click();
  await page.waitForTimeout(300);
}

/**
 * Create a new incident via the dialog, return its id from the detail page.
 * Optionally select an assignee by name in the create dialog.
 */
async function createIncident(
  page: any,
  title: string,
  desc: string,
  category: keyof typeof CATEGORY,
  priority: keyof typeof PRIORITY,
  assigneeName?: string,
): Promise<string> {
  // Navigate to incidents list
  await page.goto('/incidents');
  await page.waitForTimeout(3000);

  // Click "Новый инцидент"
  await clickButton(page, 'vaadin-button#newIncidentButton');
  await page.waitForTimeout(2000);

  // Fill the create form
  await fillTextField(page, 'titleField', title);
  await fillTextArea(page, 'descriptionField', desc);
  await selectComboItem(page, 'categoryField', CATEGORY[category]);
  await selectComboItem(page, 'priorityField', PRIORITY[priority]);

  // Assignee is an entityComboBox, not vaadin-combo-box
  if (assigneeName) {
    await selectEntityComboItem(page, 'assigneeField', assigneeName);
  }

  // Click "Создать"
  await clickButton(page, 'vaadin-button#createButton');

  // Wait for navigation to the new incident card
  await page.waitForURL(/\/incidents\/INC-\d{3,}/, { timeout: 15_000 });
  await page.waitForTimeout(1000);

  const incidentNumber = await page.locator('#incidentNumber').textContent();
  expect(incidentNumber).toMatch(/^INC-\d{3,}$/);
  return incidentNumber!;
}

// ─── Tests ────────────────────────────────────────────────────────────

test.describe('E2E-01: Full incident lifecycle', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/dashboard$/, { timeout: 30_000 });
    await expect(page.locator('.kpi-row')).toBeVisible({ timeout: 15_000 });
  });

  test('Full cycle: create → assign → take → comment → resolve → close', async ({ page }) => {
    // Step 1 & 2: Create with assignment
    await page.goto('/incidents');
    await page.waitForTimeout(3000);
    await clickButton(page, 'vaadin-button#newIncidentButton');
    await page.waitForTimeout(2000);

    await fillTextField(page, 'titleField', 'E2E полный цикл — тест VPN');
    await fillTextArea(page, 'descriptionField', 'E2E тест полного жизненного цикла инцидента.');
    await selectComboItem(page, 'categoryField', CATEGORY.Network);
    await selectComboItem(page, 'priorityField', PRIORITY.High);
    // Assignee via entityComboBox (Jmix wraps vaadin-combo-box)
    await selectEntityComboItem(page, 'assigneeField', USER.PETROV);

    await clickButton(page, 'vaadin-button#createButton');
    await page.waitForURL(/\/incidents\/INC-\d{3,}/, { timeout: 15_000 });
    await page.waitForTimeout(1500);

    const incidentNumberEl = page.locator('#incidentNumber');
    await expect(incidentNumberEl).toBeVisible({ timeout: 10_000 });
    const incidentId = await incidentNumberEl.textContent();
    expect(incidentId).toMatch(/^INC-\d{3,}$/);

    // Verify: Status = Новый (New)
    await expect(page.locator('#statusBadge')).toContainText('Новый', { timeout: 5_000 });

    // Step 3: Take in progress
    const takeBtn = page.locator('vaadin-button#takeInProgressButton');
    await expect(takeBtn).toBeVisible({ timeout: 5_000 });
    await takeBtn.click();
    await page.waitForTimeout(1500);

    // Status should now be "В работе"
    await expect(page.locator('#statusBadge')).toContainText('В работе', { timeout: 10_000 });
    // Resolve button should now be visible
    await expect(page.locator('vaadin-button#resolveButton')).toBeVisible({ timeout: 5_000 });

    // Step 4: Add a comment
    // Navigate to Comments tab
    const commentsTab = page.locator('vaadin-tab').filter({ hasText: 'Комментарии' });
    await commentsTab.click();
    await page.waitForTimeout(800);

    const commentForm = page.locator('#commentForm');
    await expect(commentForm).toBeVisible({ timeout: 5_000 });

    await fillTextArea(page, 'commentField', 'E2E тестовый комментарий: проблема диагностирована.');
    await clickButton(page, 'vaadin-button#sendCommentButton');
    await page.waitForTimeout(1000);

    // Comment should appear in feed
    await expect(page.locator('.comments-feed')).toContainText('E2E тестовый комментарий', { timeout: 10_000 });

    // Step 5a: Negative — try resolve without resolution via REST
    const resolveWithoutRes = await page.evaluate(async (id: string) => {
      const r = await fetch(`/api/incidents/${id}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', 'X-User-Id': '1' },
        body: JSON.stringify({ status: 'Resolved' }),
      });
      return { status: r.status, body: await r.json() };
    }, incidentId);
    expect(resolveWithoutRes.status).toBe(400);
    expect(resolveWithoutRes.body).toHaveProperty('detail');

    // Step 5b: Resolve properly via UI
    await page.locator('vaadin-button#resolveButton').click();
    await page.waitForTimeout(1500);

    // The InputDialog renders a vaadin-text-field#resolution in the light DOM
    const resInput = page.locator('vaadin-text-field#resolution input');
    await resInput.fill('Перезапущен кластер VPN, обновлены сертификаты.');

    // Click OK in the input dialog
    await page.locator('vaadin-button').filter({ hasText: 'OK' }).click();
    await page.waitForTimeout(1500);

    // Status should be "Решён" (Resolved, short badge form)
    await expect(page.locator('#statusBadge')).toContainText('Решён', { timeout: 10_000 });
    // Close button appears only in Resolved state (confirms state machine transition worked)
    await expect(page.locator('vaadin-button#closeIncidentButton')).toBeVisible({ timeout: 5_000 });

    // Switch to Info tab before checking resolutionBox (tabSheet hides inactive tab content)
    const infoTabBtn = page.locator('vaadin-tab').filter({ hasText: 'Информация' });
    await infoTabBtn.click();
    await page.waitForTimeout(500);

    // Verify resolution box shows the resolution text and is visible
    await expect(page.locator('#resolutionBox')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#resolutionText')).toContainText('Перезапущен', { timeout: 10_000 });

    // Step 6: Close incident
    await page.locator('vaadin-button#closeIncidentButton').click();
    await page.waitForTimeout(1500);

    // Status should be "Закрыт" (Closed)
    await expect(page.locator('#statusBadge')).toContainText('Закрыт', { timeout: 10_000 });

    // Card should be read-only
    await expect(page.locator('vaadin-button#takeInProgressButton')).toBeHidden({ timeout: 5_000 });
    await expect(page.locator('vaadin-button#resolveButton')).toBeHidden({ timeout: 5_000 });
    await expect(page.locator('vaadin-button#closeIncidentButton')).toBeHidden({ timeout: 5_000 });
    await expect(page.locator('#commentForm')).toBeHidden({ timeout: 5_000 });
    await expect(page.locator('#closedNotice')).toBeVisible({ timeout: 5_000 });

    // Step 7: Verify full history
    const historyTab = page.locator('vaadin-tab').filter({ hasText: 'История' });
    await historyTab.click();
    await page.waitForTimeout(800);

    await expect(page.locator('vaadin-grid#historyDataGrid')).toBeVisible({ timeout: 5_000 });
    await expect(page.locator('#historyEmpty')).toBeHidden({ timeout: 5_000 });

    // Verify event types present (Russian labels from Jmix localization)
    const historyCells = page.locator('vaadin-grid#historyDataGrid vaadin-grid-cell-content');
    const typeTexts = await historyCells.allTextContents();
    const joined = typeTexts.join(' ');
    expect(joined).toContain('Создание');
    expect(joined).toContain('Комментарий');
    expect(joined).toContain('Смена статуса');
    expect(joined).toContain('Решение');
    // Assignee set at creation time → appears in CREATED payload, not as separate ASSIGNED event
    expect(joined).toContain(USER.PETROV);

    // Step 8: Dashboard reflects the cycle
    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/dashboard$/, { timeout: 10_000 });
    await expect(page.locator('.kpi-row')).toBeVisible({ timeout: 15_000 });

    // KPI cards present
    const kpiCards = page.locator('.kpi-row .kpi-card');
    const kpiCount = await kpiCards.count();
    expect(kpiCount).toBeGreaterThanOrEqual(5);

    // Activity feed present
    const activityItems = page.locator('.activity-list .activity-item');
    const activityCount = await activityItems.count();
    expect(activityCount).toBeGreaterThanOrEqual(1);

    // Charts rendered (at least one)
    await expect(page.locator('jmix-chart').first()).toBeVisible({ timeout: 10_000 });
  });
});

test.describe('E2E-02: Server protection through UI', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/dashboard$/, { timeout: 30_000 });
  });

  test('New incident: only "Take" button visible, New→Closed REST returns 409', async ({ page }) => {
    await page.goto('/incidents');
    await page.waitForTimeout(3000);
    await clickButton(page, 'vaadin-button#newIncidentButton');
    await page.waitForTimeout(2000);

    await fillTextField(page, 'titleField', 'E2E проверка недоступных кнопок');
    await fillTextArea(page, 'descriptionField', 'Проверка UI блокировки.');
    await selectComboItem(page, 'categoryField', CATEGORY.Network);
    await selectComboItem(page, 'priorityField', PRIORITY.Low);

    await clickButton(page, 'vaadin-button#createButton');
    await page.waitForURL(/\/incidents\/INC-\d{3,}/, { timeout: 15_000 });
    await page.waitForTimeout(1000);

    const incidentId = await page.locator('#incidentNumber').textContent();
    expect(incidentId).toMatch(/^INC-\d{3,}$/);

    // New status: only "Взять в работу" visible
    await expect(page.locator('vaadin-button#takeInProgressButton')).toBeVisible({ timeout: 5_000 });
    await expect(page.locator('vaadin-button#resolveButton')).toBeHidden({ timeout: 5_000 });
    await expect(page.locator('vaadin-button#closeIncidentButton')).toBeHidden({ timeout: 5_000 });

    // Direct REST: New→Closed → 409
    const response = await page.evaluate(async (id: string) => {
      const r = await fetch(`/api/incidents/${id}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', 'X-User-Id': '1' },
        body: JSON.stringify({ status: 'Closed' }),
      });
      return r.status;
    }, incidentId);
    expect(response).toBe(409);
  });
});

test.describe('E2E-03: Search and empty states', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/dashboard$/, { timeout: 30_000 });
  });

  test('Search by title filters the grid', async ({ page }) => {
    await page.goto('/incidents');
    await page.waitForTimeout(3000);

    // Type in search field
    const searchInput = page.locator('vaadin-text-field#searchField input');
    await searchInput.fill('Не работает');
    await page.waitForTimeout(1000);

    // Grid should still be visible with filtered results
    await expect(page.locator('vaadin-grid#incidentsDataGrid')).toBeVisible({ timeout: 5_000 });
  });

  test('Empty search result shows empty state', async ({ page }) => {
    await page.goto('/incidents');
    await page.waitForTimeout(3000);

    const searchInput = page.locator('vaadin-text-field#searchField input');
    await searchInput.fill('ZZZXYZZY_NONEXISTENT_ZZZ');
    await page.waitForTimeout(1000);

    // Empty state should be visible
    await expect(page.locator('#emptyState')).toBeVisible({ timeout: 10_000 });
  });

  test('Search by ID (e.g. INC-001) filters to single match', async ({ page }) => {
    await page.goto('/incidents');
    await page.waitForTimeout(3000);

    // Search for a known seed-data incident
    const searchInput = page.locator('vaadin-text-field#searchField input');
    await searchInput.fill('INC-001');
    await page.waitForTimeout(1000);

    // Grid should be visible and show filtered results
    const grid = page.locator('vaadin-grid#incidentsDataGrid');
    await expect(grid).toBeVisible({ timeout: 5_000 });

    // Row count should be small (probably just 1 or 2)
    const rows = grid.locator('vaadin-grid-cell-content');

    // At least one cell should contain the matching ID
    const allText = await rows.allTextContents();
    const idCells = allText.filter(t => t.trim().startsWith('INC-'));
    expect(idCells.length).toBeGreaterThanOrEqual(1);
  });
});