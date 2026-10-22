// Снимки прототипа: node proto.mjs <страница.html> <папка> <имя=запрос>…  (Playwright из ~/Dev/.toolchains)
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');   // свой путь к playwright — PLAYWRIGHT_MODULE
const [,, page, out, ...specs] = process.argv;
const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 1300, height: 900 }, deviceScaleFactor: 2 });
for (const s of specs) {
  const i = s.indexOf('='); const name = s.slice(0, i); const q = s.slice(i + 1);
  await p.goto('about:blank'); await p.goto(`file://${page}?proto&${q}`); await p.waitForTimeout(1200);
  await (await p.$('.scr')).screenshot({ path: `${out}/${name}.proto.png` });
}
await b.close();
