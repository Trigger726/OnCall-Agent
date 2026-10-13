// Vendor Xiaomi's original web-font files without altering their glyphs or tables.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), assert = require('node:assert/strict');
const directory = path.resolve(__dirname, '../web/public/assets/fonts');
const cssUrl = 'https://cdn-font.hyperos.mi.com/font/css?family=MiSans_VF:VF:Chinese_Simplify,Latin&display=swap';
const licenseUrl = 'https://hyperos.mi.com/font-download/MiSans字体知识产权许可协议.pdf';
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
async function download(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(30000) });
  assert.equal(response.ok, true, `Download failed: ${response.status} ${url}`);
  return Buffer.from(await response.arrayBuffer());
}
(async () => {
  fs.mkdirSync(directory, { recursive: true });
  const original = await download(cssUrl), css = original.toString('utf8');
  const urls = [...new Set([...css.matchAll(/url\("(https:\/\/cdn-file\.hyperos\.mi\.com\/[^"\s]+\.woff2)"\)/g)].map(match => match[1]))];
  assert.ok(urls.length > 0, 'Official CSS contained no WOFF2 fonts');
  const files = [], queue = [...urls];
  await Promise.all(Array.from({ length: 6 }, async () => {
    while (queue.length) {
      const url = queue.shift(), file = path.basename(new URL(url).pathname), bytes = await download(url);
      assert.equal(bytes.subarray(0, 4).toString(), 'wOF2');
      fs.writeFileSync(path.join(directory, file), bytes);
      files.push({ file, url, bytes: bytes.length, sha256: sha(bytes) });
    }
  }));
  // Mechanical URL rewrite only: retain the publisher's family, variable weights and unicode ranges.
  const local = css.replace(/src:\s*url\("(https:\/\/cdn-file\.hyperos\.mi\.com\/[^"\s]+\.woff2)"\)[^;]+;/g,
    (_, url) => `src: url("/assets/fonts/${path.basename(new URL(url).pathname)}") format("woff2");`);
  assert.equal(/https?:\/\//.test(local), false);
  fs.writeFileSync(path.join(directory, 'misans.css'), local);
  const license = await download(licenseUrl);
  assert.equal(license.subarray(0, 4).toString(), '%PDF');
  fs.writeFileSync(path.join(directory, 'MiSans-LICENSE.pdf'), license);
  const manifest = { family: 'MiSans VF', copyright: 'Xiaomi Inc. / 小米科技有限责任公司',
    source: 'https://hyperos.mi.com/font/zh/download/', cssUrl, originalCssSha256: sha(original),
    licenseUrl, licenseSha256: sha(license), localCssSha256: sha(Buffer.from(local)),
    modification: 'CSS URLs localized; original publisher WOFF2 bytes unchanged; no glyph editing or subsetting',
    files: files.sort((a, b) => a.file.localeCompare(b.file)) };
  fs.writeFileSync(path.join(directory, 'source.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(JSON.stringify({ files: files.length, bytes: files.reduce((sum, file) => sum + file.bytes, 0), directory }));
})().catch(error => { console.error(error); process.exitCode = 1; });
