const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const hashes = JSON.parse(fs.readFileSync(path.join(__dirname, 'vendor/source-hashes.json'), 'utf8').replace(/^\uFEFF/, ''));
for (const item of hashes) {
  const digest = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  if (digest(path.join(__dirname, 'vendor', item.path)) !== item.sha256) throw new Error(`Vendor changed: ${item.path}`);
  if (require.main === module && process.argv[2] && digest(path.join(process.argv[2], item.path)) !== item.sha256) throw new Error(`Upstream changed: ${item.path}`);
}
console.log(`PASS: ${hashes.length} original source files match their recorded SHA-256 hashes.`);
