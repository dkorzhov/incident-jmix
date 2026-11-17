import { test, expect } from '@playwright/test';

// Smoke: приложение поднято (docker compose up -d db + ./gradlew bootRun),
// авто-вход (ADR-003): переход на '/' сразу ведёт на Dashboard без формы логина.
test('app auto-login opens dashboard', async ({ page }) => {
  const response = await page.goto('/');
  expect(response?.status()).toBe(200);

  // ADR-003: after auto-login, the default view is the Dashboard
  await expect(page).toHaveURL(/\/dashboard$/, { timeout: 30_000 });

  // KPI cards and sidebar navigation are visible
  await expect(page.locator('.kpi-row')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('link', { name: 'Инциденты' })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('link', { name: 'Пользователи' })).toBeVisible({ timeout: 10_000 });
});