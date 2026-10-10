// node visualizer/test-renderer.cjs PATH/TO/node_modules [alternate-source-root]
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const dependencies = path.resolve(process.argv[2]);
const { chromium } = require(path.join(dependencies, '@playwright/test'));
const esbuild = require(path.join(dependencies, 'esbuild'));
const source = path.resolve(process.argv[3] || path.join(__dirname, 'vendor'));
const script = esbuild.buildSync({ stdin: { contents: `
  export {createVideoRenderer} from './src/lib/video/VideoRenderer';
  export {OfflineSpectrum} from './src/lib/video/OfflineSpectrum';
  export {convertGeminiJson} from './src/lib/geminiImport';
  export {parseTranscript} from './src/lib/parser';`, resolveDir: source },
  bundle: true, write: false, format: 'iife', globalName: 'TimingTest' }).outputFiles[0].text;
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage();
    const font = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/visualizer/fonts/caveat.ttf')).toString('base64');
    await page.setContent(`<style>@font-face{font-family:Caveat;src:url(data:font/ttf;base64,${font});font-weight:100 900}</style>`);
    await page.addScriptTag({ content: script });
    const checks = await page.evaluate(async () => {
      const json = JSON.stringify([
        {text:'Gather',start:11.30,end:12,language:'en',phrase_id:'intro',uncertain:false},
        {text:'around',start:12.10,end:13.20,language:'en',phrase_id:'intro',uncertain:false},
        {text:'Family',start:16.50,end:19.20,language:'en',phrase_id:'intro-2',uncertain:false},
        {text:'Fuego',start:35.40,end:36.10,language:'es',phrase_id:'verse',uncertain:false},
        {text:'Fire',start:37.30,end:37.90,language:'en',phrase_id:'response',uncertain:false},
      ]);
      const segments = TimingTest.parseTranscript(JSON.stringify(TimingTest.convertGeminiJson(json)), 'json').segments;
      const checks = [];
      for (const [width, height] of [[720,1280], [1280,720], [1080,1080]]) {
        const renderer = await TimingTest.createVideoRenderer({ width,height,fps:30,duration:40,theme:'sketchbook',title:'English intro',segments });
        const spectrum = new TimingTest.OfflineSpectrum([new Float32Array(0)],44100);
        for (const seconds of [11.5,12.5,17,35.8,37.5]) {
          const frame = renderer.draw(seconds,spectrum);
          checks.push({width,height,seconds,horizon:frame.layout.horizon,translationTop:frame.layout.translation?.top});
        }
        renderer.destroy();
      }
      return {checks, first:segments[0].translationWords[0].start, second:segments[1].words[0].start};
    });
    assert.equal(checks.first, 11.30); assert.equal(checks.second, 35.40);
    for (const item of checks.checks) assert.ok(item.translationTop >= item.horizon,
      `${item.width}x${item.height} at ${item.seconds}s: reflection top ${item.translationTop} is clipped by horizon ${item.horizon}`);
    console.log(`PASS: ${checks.checks.length} English-only/bilingual lyric layouts retain their word timestamps and stay below the clipping waterline.`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
