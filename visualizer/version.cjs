const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const hashes = JSON.parse(fs.readFileSync(path.join(__dirname, 'vendor/source-hashes.json'), 'utf8').replace(/^\uFEFF/, ''));
const hash = crypto.createHash('sha256');
for (const item of hashes) hash.update(item.path + ':' + item.sha256 + '\n');
for (const font of ['caveat.ttf', 'patrick-hand.ttf']) hash.update(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/visualizer/fonts', font)));
module.exports = hash.digest('hex');
