import { test, expect } from '@playwright/test';

// Smoke: приложение поднято (docker compose up -d db + ./gradlew bootRun),
// стартовая страница отдаёт форму логина шаблона.
test('app serves login page', async ({ page }) => {
  const response = await page.goto('/');
  expect(response?.status()).toBe(200);
  await expect(page.getByRole('textbox', { name: 'Username' })).toBeVisible({ timeout: 30_000 });
});
