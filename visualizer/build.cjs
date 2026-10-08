// Rebuild with: node visualizer/build.cjs [path-to-installed-esbuild]
const path = require('node:path');
const fs = require('node:fs');
const esbuild = require(process.argv[2] || 'esbuild');
const root = __dirname, destination = path.resolve(root, '../app/src/main/assets/visualizer');
require('./verify.cjs');
fs.mkdirSync(destination, { recursive: true });
esbuild.buildSync({ entryPoints: [path.join(root, 'app.ts')], bundle: true, format: 'iife', target: 'chrome100', minify: true,
  outfile: path.join(destination, 'app.js'), legalComments: 'eof' });
for (const name of ['index.html', 'style.css']) fs.copyFileSync(path.join(root, name), path.join(destination, name));
fs.writeFileSync(path.join(destination, 'renderer-version.txt'), require('./version.cjs'));
fs.copyFileSync(path.join(root, 'vendor/src/assets/prompts/gemini-suno-word-timings.txt'), path.join(destination, 'gemini-prompt.txt'));
console.log('Bundled original Visual-Music-Lyrics engines and phone controls.');
