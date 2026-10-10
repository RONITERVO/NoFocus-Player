// A separate, authenticated LAN service. The desktop visualizer's loopback APIs stay private.
const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const os = require('node:os');
const https = require('node:https');
const http = require('node:http');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { pipeline } = require('node:stream/promises');
const { Transform } = require('node:stream');
const { WebSocketServer } = require('ws');
const rendererVersion = require('../visualizer/version.cjs');
const MB = 1024 * 1024, RETENTION = 24 * 60 * 60 * 1000;
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const equal = (a, b) => typeof a === 'string' && typeof b === 'string' && Buffer.byteLength(a) === Buffer.byteLength(b) && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
function fail(message, status = 400) { const error = new Error(message); error.status = status; throw error; }
function validate(config) {
  if (!config || !UUID.test(config.id) || config.rendererVersion !== rendererVersion) fail('Update the phone and PC companion together: renderer versions must match.');
  for (const key of ['width', 'height']) if (!Number.isInteger(config[key]) || config[key] < 320 || config[key] > 1920 || config[key] % 2) fail('Invalid canvas size.');
  if (![24,30,60].includes(config.fps) || !Number.isFinite(config.duration) || config.duration <= 0 || config.duration > 1200) fail('Invalid duration or frame rate.');
  if (!['sketchbook', 'signal-bloom'].includes(config.theme) || !['publish', 'lossless'].includes(config.mode) || !['preserve', 'aac'].includes(config.audio)) fail('Invalid export profile.');
  if (typeof config.title !== 'string' || config.title.length > 160 || typeof config.codec !== 'string' || config.codec.length > 40) fail('Invalid song metadata.');
  if (!Number.isSafeInteger(config.sourceBytes) || config.sourceBytes < 1 || config.sourceBytes > 768 * MB) fail('Source must be under 768 MB.');
  if (!Number.isSafeInteger(config.analysisBytes) || config.analysisBytes < 4 || config.analysisBytes % 4 || Math.abs(config.analysisBytes / (44100 * 4) - config.duration) > .1) fail('Analysis does not match song duration.');
  if (!Array.isArray(config.segments) || config.segments.length > 10000) fail('Invalid lyric cues.');
  for (const segment of config.segments) {
    if (!segment || !Number.isFinite(segment.start) || !Number.isFinite(segment.end) || segment.start < 0 || segment.end <= segment.start || segment.end > config.duration + .05) fail('Invalid lyric timing.');
    for (const key of ['primary', 'translation', 'secondary']) if (segment[key] != null && (typeof segment[key] !== 'string' || segment[key].length > 20000)) fail('Invalid lyric text.');
    for (const key of ['words', 'translationWords']) if (segment[key] != null) {
      if (!Array.isArray(segment[key]) || segment[key].length > 2000) fail('Invalid lyric words.');
      // The shared parser normalizes Gemini's text field to MusicLyricWord.value.
      for (const word of segment[key]) if (!word || typeof word.value !== 'string' || !word.value.trim() || word.value.length > 2000 || !Number.isFinite(word.start) || !Number.isFinite(word.end) || word.start < 0 || word.end <= word.start || word.end > config.duration + .05) fail('Invalid word timing.');
    }
  }
  return config;
}
async function body(req, max) {
  const chunks = []; let size = 0;
  for await (const part of req) { size += part.length; if (size > max) fail('Request is too large.', 413); chunks.push(part); }
  return Buffer.concat(chunks);
}
async function atomic(file, object) {
  await fsp.writeFile(file + '.tmp', JSON.stringify(object), { mode: 0o600 });
  await fsp.rename(file + '.tmp', file);
}
async function freeSpace(dir, minimum) {
  const stat = await fsp.statfs(dir);
  if (stat.bavail * stat.bsize < minimum) fail('Free more PC disk space before exporting.', 507);
}
function json(res, data, status = 200) { res.writeHead(status, {'Content-Type':'application/json','Cache-Control':'no-store'}); res.end(JSON.stringify(data)); }
async function identity(state) {
  const file = path.join(state, 'identity.json');
  if (fs.existsSync(file)) return JSON.parse(await fsp.readFile(file, 'utf8'));
  const notAfterDate = new Date(); notAfterDate.setFullYear(notAfterDate.getFullYear() + 5);
  const pem = await require('selfsigned').generate([{name:'commonName',value:'NoFocus Export'}], { algorithm:'sha256', keySize:2048, notAfterDate });
  const result = {key:pem.private, cert:pem.cert, token:crypto.randomBytes(32).toString('hex')};
  await atomic(file, result); return result;
}
async function start(options = {}) {
  require('../visualizer/verify.cjs');
  const state = path.resolve(options.state || process.env.NOFOCUS_EXPORT_STATE || path.join(process.env.LOCALAPPDATA || os.homedir(), 'NoFocusExport'));
  await fsp.mkdir(state, {recursive:true, mode:0o700});
  const jobsRoot = path.join(state, 'jobs'); await fsp.mkdir(jobsRoot, {recursive:true, mode:0o700});
  const credentials = await identity(state);
  const fingerprint = new crypto.X509Certificate(credentials.cert).fingerprint256.replace(/:/g,'').toLowerCase();
  const ffmpeg = options.ffmpeg || process.env.NOFOCUS_FFMPEG || require('ffmpeg-static');
  const script = (await require('esbuild').build({entryPoints:[path.join(__dirname,'renderer.ts')],bundle:true,write:false,format:'iife',target:'chrome100'})).outputFiles[0].contents;
  const jobs = new Map(); let running = null, closing = false;
  const directory = id => path.join(jobsRoot, id);
  const save = job => {
    job.updated = Date.now();
    const snapshot = {id:job.id,config:job.config,status:job.status,frames:job.frames,total:job.total,extension:job.extension,error:job.error || '',created:job.created,updated:job.updated,queuedAt:job.queuedAt,started:job.started,timings:job.timings,encoder:job.encoder};
    job.saving = (job.saving || Promise.resolve()).catch(() => {}).then(() => atomic(path.join(directory(job.id),'job.json'),snapshot));
    return job.saving;
  };
  const queued = () => [...jobs.values()].filter(j => j.status === 'queued').sort((a,b) => (a.queuedAt || a.created) - (b.queuedAt || b.created));
  const view = job => ({id:job.id,status:job.status,frames:job.frames,total:job.total,extension:job.extension,error:job.error || '',title:job.config.title,timings:job.timings,encoder:job.encoder,
    elapsedMs:job.status === 'rendering' ? Date.now() - job.started : job.timings?.elapsedMs || 0,
    position:job.status === 'queued' ? queued().indexOf(job) + 1 : 0});
  const expired = job => !['queued','rendering'].includes(job.status) && Date.now() - (job.updated || job.created) > RETENTION;
  for (const id of await fsp.readdir(jobsRoot)) {
    if (!UUID.test(id)) continue;
    try {
      const job = JSON.parse(await fsp.readFile(path.join(directory(id),'job.json'),'utf8'));
      validate(job.config);
      if (expired(job)) { await fsp.rm(directory(id), {recursive:true,force:true}); continue; }
      if (job.status === 'rendering') { job.status = 'queued'; job.error = ''; job.frames = 0; await save(job); }
      jobs.set(id, job);
    } catch { await fsp.rm(directory(id), {recursive:true,force:true}); }
  }
  async function cleanup() {
    for (const job of jobs.values()) if (job !== running && !job.uploading && !job.downloading && expired(job)) {
      jobs.delete(job.id); await fsp.rm(directory(job.id), {recursive:true,force:true});
    }
  }
  async function render(job) {
    const dir = directory(job.id), config = job.config, frameBytes = config.width * config.height * 4;
    const token = crypto.randomBytes(24).toString('hex');
    let browser, local, frames, encoder, code, stderr = '', received = 0, sending = false, format = 'rgba';
    const timeout = setTimeout(() => {job.status = 'failed';job.error = 'PC export exceeded two hours.';if (job.stop) job.stop();},2 * 60 * 60 * 1000);
    job.status = 'rendering'; job.frames = 0; job.error = ''; job.timings = {encodeMs:0}; const started = job.started = Date.now(); await save(job);
    try {
      await freeSpace(state, 1024 * MB);
      job.stop = () => { if (encoder) encoder.kill(); if (browser) void browser.close(); };
      local = http.createServer(async (req,res) => {
        try {
          const base = `http://127.0.0.1:${local.address().port}`;
          if (req.headers.host !== `127.0.0.1:${local.address().port}` || (req.headers.origin && req.headers.origin !== base)) fail('Forbidden.',403);
          const url = new URL(req.url,base);
          if (!url.pathname.startsWith(`/${token}/`)) fail('Not found.',404);
          const name = url.pathname.slice(token.length + 2);
          if (req.method === 'GET' && name === '') {
            res.writeHead(200, {'Content-Type':'text/html','Content-Security-Policy':`default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; font-src 'self'; connect-src 'self' ws://127.0.0.1:${local.address().port}`});
            res.end('<style>@font-face{font-family:Caveat;src:url(caveat.ttf);font-weight:100 900}@font-face{font-family:"Patrick Hand";src:url(patrick-hand.ttf)}</style><script src="renderer.js"></script>');
          } else if (req.method === 'GET' && name === 'renderer.js') {res.writeHead(200,{'Content-Type':'application/javascript'});res.end(script);}
          else if (req.method === 'GET' && ['caveat.ttf','patrick-hand.ttf','analysis'].includes(name)) {
            res.writeHead(200,{'Content-Type':name === 'analysis' ? 'application/octet-stream' : 'font/ttf'});
            await pipeline(fs.createReadStream(name === 'analysis' ? path.join(dir,'analysis') : path.join(__dirname,'../app/src/main/assets/visualizer/fonts',name)),res);
          } else fail('Not found.',404);
        } catch (error) { if (!res.headersSent && !res.destroyed) {res.writeHead(error.status || 500);res.end(error.message);} else res.destroy(); }
      });
      await new Promise(resolve => local.listen(0,'127.0.0.1',resolve));
      frames = new WebSocketServer({noServer:true,maxPayload:32 * MB + 8,perMessageDeflate:false});
      local.on('upgrade', (req,socket,head) => {
        const host = `127.0.0.1:${local.address().port}`;
        if (req.headers.host !== host || req.headers.origin !== `http://${host}` || req.url !== `/${token}/frames` || frames.clients.size) {socket.destroy();return;}
        frames.handleUpgrade(req,socket,head,connection => frames.emit('connection',connection));
      });
      frames.on('connection', connection => {
        connection.on('error', () => {});
        connection.on('message', async (packet,binary) => {
          try {
            if (!binary || packet.length < 8 || sending || job.status !== 'rendering') fail('Invalid frame packet.');
            const index = packet.readUInt32LE(0), count = packet.readUInt32LE(4);
            if (index !== received || count < 1 || count > 4 || index + count > job.total || (format === 'rgba' ? packet.length !== 8 + frameBytes * count : packet.length <= 8)) fail('Invalid frame sequence or size.');
            sending = true;
            if (received % 120 === 0) await freeSpace(state,256 * MB);
            if (encoder.exitCode !== null || encoder.stdin.destroyed) fail('Encoder stopped: ' + stderr);
            const beforeEncode = performance.now();
            await new Promise((resolve,reject) => encoder.stdin.write(packet.subarray(8), error => error ? reject(error) : resolve()));
            job.timings.encodeMs += performance.now() - beforeEncode;
            received += count; job.frames = received;
            connection.send(JSON.stringify({frames:received}));
          } catch (error) {connection.send(JSON.stringify({error:error.message}), () => connection.close());}
          finally {sending = false;}
        });
      });
      const { chromium } = require('playwright');
      browser = await chromium.launch({channel:options.channel || process.env.NOFOCUS_BROWSER || 'msedge',headless:true});
      if (job.status !== 'rendering') throw new Error('Export cancelled.');
      const page = await browser.newPage({viewport:{width:config.width,height:config.height},deviceScaleFactor:1});
      await page.goto(`http://127.0.0.1:${local.address().port}/${token}/`);
      format = await page.evaluate(config => window.prepare(config), {...config, software:options.software || process.env.NOFOCUS_PC_ENCODER === 'software'});
      job.encoder = format === 'h264' ? 'PC hardware H.264' : config.mode === 'lossless' ? 'PC lossless RGB' : 'PC software H.264';
      if (job.status !== 'rendering') throw new Error('Export paused or cancelled.');
      const input = format === 'h264' ? ['-f','h264','-framerate',String(config.fps),'-i','pipe:0'] : ['-f','rawvideo','-pix_fmt','rgba','-video_size',`${config.width}x${config.height}`,'-framerate',String(config.fps),'-i','pipe:0'];
      const video = format === 'h264' ? ['-c:v','copy'] : ['-c:v',config.mode === 'lossless' ? 'libx264rgb' : 'libx264','-preset','ultrafast','-crf',config.mode === 'lossless' ? '0' : '17',
        '-pix_fmt',config.mode === 'lossless' ? 'rgb24' : 'yuv420p','-threads','4'];
      const args = ['-hide_banner','-loglevel','error','-y',...input,
        '-protocol_whitelist','file,pipe','-i',path.join(dir,'source'),'-map','0:v:0','-map','1:a:0',
        ...video,'-c:a',config.audio === 'aac' ? 'aac' : 'copy'];
      if (config.audio === 'aac') args.push('-b:a','320k');
      if (job.extension === 'mp4') args.push('-movflags','+faststart');
      args.push(path.join(dir, `video.${job.extension}`));
      encoder = spawn(ffmpeg,args,{windowsHide:true,stdio:['pipe','ignore','pipe']});
      encoder.stdin.on('error', () => {});
      encoder.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-2000); });
      code = new Promise((resolve,reject) => { encoder.once('error',reject); encoder.once('close',resolve); }); code.catch(() => {});
      const rendering = page.evaluate(config => window.render(config), config);
      Object.assign(job.timings, await Promise.race([rendering, code.then(() => { throw new Error('Encoder stopped early: ' + stderr); })]));
      encoder.stdin.end();
      if (await code !== 0 || received !== job.total) throw new Error('Video encoding failed: ' + stderr);
      if (job.status !== 'rendering') throw new Error('Export cancelled.');
      const output = path.join(dir, `video.${job.extension}`);
      job.status = 'complete';
      // A streaming checksum lets the phone validate a download before publishing it.
      const hash = crypto.createHash('sha256'); for await (const chunk of fs.createReadStream(output)) hash.update(chunk);
      job.sha256 = hash.digest('hex');
    } catch (error) { if (job.status === 'rendering') {job.status = 'failed';job.error = error.message;} }
    finally {
      clearTimeout(timeout);
      job.timings.elapsedMs = Date.now() - started;
      job.stop = null;
      if (encoder && encoder.exitCode === null) encoder.kill();
      if (code) await code.catch(() => {});
      if (browser) await browser.close().catch(() => {});
      if (frames) {for (const connection of frames.clients) connection.terminate();await new Promise(resolve => frames.close(resolve));}
      if (local) {local.closeAllConnections();await new Promise(resolve => local.close(resolve));}
      await save(job);
      if (job.status === 'complete') for (const name of ['source','analysis']) await fsp.rm(path.join(dir,name),{force:true});
      if (job.status !== 'complete') await fsp.rm(path.join(dir,`video.${job.extension}`),{force:true});
    }
  }
  function pump() {
    if (running || closing) return;
    const job = queued()[0]; if (!job) return;
    running = job;
    job.task = render(job).catch(error => { job.status = 'failed'; job.error = error.message; }).finally(() => {running = null;pump();});
  }
  const server = https.createServer({...credentials,minVersion:'TLSv1.2'}, async (req,res) => {
    try {
      if (req.headers.origin || !equal(req.headers.authorization, 'Bearer ' + credentials.token)) fail('Pair this phone with the PC first.',401);
      const url = new URL(req.url,'https://localhost'), route = url.pathname;
      if (req.method === 'GET' && route === '/v1/info') return json(res,{name:os.hostname(),protocol:2,rendererVersion,busy:!!running});
      if (req.method === 'GET' && route === '/v1/jobs') return json(res,{jobs:[...jobs.values()].map(view)});
      if (req.method === 'POST' && route === '/v1/jobs') {
        const config = validate(JSON.parse((await body(req,2 * MB)).toString('utf8')));
        const previous = jobs.get(config.id);
        if (previous) { if (JSON.stringify(previous.config) !== JSON.stringify(config)) fail('Job identity conflict.',409);if (previous.initializing) await previous.initializing;return json(res,view(previous)); }
        await cleanup();
        await freeSpace(state, config.sourceBytes + config.analysisBytes + 1024 * MB);
        const existing = jobs.get(config.id);
        if (existing) {if (JSON.stringify(existing.config) !== JSON.stringify(config)) fail('Job identity conflict.',409);if (existing.initializing) await existing.initializing;return json(res,view(existing));}
        if (jobs.size >= 16 || [...jobs.values()].filter(j => j.status !== 'complete').length >= 8) fail('The PC queue is full (8 unfinished / 16 retained). Save or remove an existing export first.',429);
        const job = {id:config.id,config,status:'uploading',frames:0,total:Math.ceil(config.duration * config.fps),created:Date.now(),
          extension:config.mode === 'lossless' || (config.audio === 'preserve' && config.codec !== 'aac') ? 'mkv' : 'mp4'};
        jobs.set(job.id,job);
        job.initializing = (async () => {await fsp.mkdir(directory(job.id),{recursive:true,mode:0o700});await save(job);})();
        try {await job.initializing;} catch (error) {jobs.delete(job.id);throw error;} finally {job.initializing = null;}
        return json(res,view(job),201);
      }
      const match = /^\/v1\/jobs\/([a-f0-9-]{36})(?:\/(source|analysis|start|pause|file))?$/.exec(route);
      if (!match || !UUID.test(match[1])) fail('Not found.',404);
      const job = jobs.get(match[1]); if (!job) fail('This PC export expired or was removed. Start it again.',404);
      const part = match[2], dir = directory(job.id);
      if (req.method === 'GET' && !part) return json(res,view(job));
      if (job.changing) fail('This export is changing state. Try again.',409);
      if (req.method === 'DELETE' && !part) {
        job.changing = true;
        if (job.downloading) {job.changing = false;fail('Wait for the download to finish.',409);}
        job.status = 'cancelled'; if (job.uploadRequest) job.uploadRequest.destroy(); if (job.stop) job.stop();
        if (job.task) await job.task;
        if (job.uploadDone) await job.uploadDone;
        jobs.delete(job.id); await fsp.rm(dir,{recursive:true,force:true});return json(res,{cancelled:true});
      }
      if (req.method === 'PUT' && ['source','analysis'].includes(part)) {
        const size = job.config[part === 'source' ? 'sourceBytes' : 'analysisBytes'];
        if (job.status !== 'uploading' || job.uploading || Number(req.headers['content-length']) !== size) fail('Invalid upload size or state.',409);
        job.uploading = true;job.uploadRequest = req;
        let done;job.uploadDone = new Promise(resolve => {done = resolve;});
        let bytes = 0;
        try {
          await pipeline(req,new Transform({transform(chunk,encoding,callback){bytes += chunk.length;callback(bytes > size ? new Error('Upload too large.') : null,chunk);}}),fs.createWriteStream(path.join(dir,part + '.tmp'),{mode:0o600}));
          if (bytes !== size || job.status !== 'uploading') fail('Incomplete or cancelled upload.');
          await fsp.rename(path.join(dir,part + '.tmp'),path.join(dir,part));return json(res,{bytes});
        } finally {job.uploading = false;job.uploadRequest = null;done();await fsp.rm(path.join(dir,part + '.tmp'),{force:true});}
      }
      if (req.method === 'POST' && part === 'pause') {
        if (!['queued','rendering','paused'].includes(job.status)) fail('Only queued or rendering exports can be paused.',409);
        job.changing = true;
        try {
          job.status = 'paused';if (job.stop) job.stop();if (job.task) await job.task;
          await save(job);pump();return json(res,view(job));
        } finally {job.changing = false;}
      }
      if (req.method === 'POST' && part === 'start') {
        if (['uploading','paused','failed'].includes(job.status)) {
          if (job.uploading) fail('Upload is still in progress.',409);
          job.changing = true;
          try {
            for (const name of ['source','analysis']) if (!fs.existsSync(path.join(dir,name)) || (await fsp.stat(path.join(dir,name))).size !== job.config[name === 'source' ? 'sourceBytes' : 'analysisBytes']) fail('Incomplete upload. Remove this job and upload again.',409);
            job.status = 'queued';job.error = '';job.queuedAt = Date.now();await save(job);pump();
          } finally {job.changing = false;}
        }
        return json(res,view(job));
      }
      if (req.method === 'GET' && part === 'file') {
        if (job.status !== 'complete') fail('Export is not finished.',409);
        const file = path.join(dir,`video.${job.extension}`), stat = await fsp.stat(file);
        // Recompute after a restart; only completed files survive.
        if (!job.sha256) {const hash = crypto.createHash('sha256');for await (const chunk of fs.createReadStream(file)) hash.update(chunk);job.sha256 = hash.digest('hex');}
        job.downloading = (job.downloading || 0) + 1;
        let released = false;
        const release = () => {if (!released) {released = true;job.downloading--;}};
        res.once('finish',release);res.once('close',release);
        try {res.writeHead(200,{'Content-Type':'application/octet-stream','Content-Length':stat.size,'X-Content-SHA256':job.sha256,'Cache-Control':'no-store'});await pipeline(fs.createReadStream(file),res);}
        finally {release();}
        return;
      }
      fail('Not found.',404);
    } catch (error) {if (!res.headersSent && !res.destroyed) json(res,{error:error.status ? error.message : 'PC export failed: ' + error.message},error.status || 500);else res.destroy();}
  });
  server.requestTimeout = 15 * 60 * 1000;server.headersTimeout = 15000;server.maxConnections = 16;
  await new Promise((resolve,reject) => {server.once('error',reject);server.listen(options.port ?? Number(process.env.NOFOCUS_EXPORT_PORT || 49632),options.host || '0.0.0.0',resolve);});
  const port = server.address().port;
  const addresses = Object.values(os.networkInterfaces()).flat().filter(item => item && item.family === 'IPv4' && !item.internal).map(item => item.address);
  const pairing = host => ({version:1,name:os.hostname(),url:`https://${host}:${port}`,fingerprint,token:credentials.token});
  const timer = setInterval(() => cleanup().catch(() => {}),60000);timer.unref();pump();
  async function close() {
    closing = true;clearInterval(timer);
    for (const job of jobs.values()) {if (job.status === 'rendering') job.status = 'queued';if (job.uploadRequest) job.uploadRequest.destroy();if (job.stop) job.stop();}
    await Promise.allSettled([...jobs.values()].map(job => job.task).filter(Boolean));
    server.closeAllConnections();await new Promise(resolve => server.close(resolve));
  }
  return {port,state,jobs,pairing,addresses,close};
}
async function main() {
  const app = await start();
  const cards = [];
  for (const address of app.addresses) {
    const pairing = app.pairing(address), code = 'nofocus-pc-v1:' + Buffer.from(JSON.stringify(pairing)).toString('base64url');
    const link = 'nofocus-export://pair?data=' + code.slice(14);
    const qr = await require('qrcode').toDataURL(link,{width:360,margin:2});
    cards.push(`<section><h2>${address}</h2><img src="${qr}" alt="Scan to pair"><p>Scan with the phone camera, or copy this code into Visual music → Export → Pair PC.</p><textarea readonly rows="5">${code}</textarea></section>`);
  }
  const file = path.join(app.state,'pairing.html');
  await fsp.writeFile(file,`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>NoFocus PC export</title><style>body{font:18px system-ui;background:#0d171b;color:#eef4f1;max-width:780px;margin:40px auto;padding:20px}section{margin:30px 0;padding:20px;background:#1b2a2e;border-radius:20px}textarea{width:100%;box-sizing:border-box}img{max-width:100%}</style><h1>NoFocus PC export</h1><p>Keep this companion running. Connect your phone to the same Wi-Fi. Choose the address for that network below. Transfers are encrypted. Finished videos remain available for 24 hours after completion.</p>${cards.join('')}<p>This pairing code grants access to this companion. Keep it private. To revoke pairing, stop the companion and remove identity.json from this folder.</p>`);
  console.log(`NoFocus export companion listening on port ${app.port}.\nPairing page: ${file}\nKeep this window running. Press Ctrl+C to stop.`);
  if (process.argv.includes('--open') && process.platform === 'win32') {
    // Fixed executable, argument passed without a shell; never interpolate a song title.
    const edge = path.join(process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)','Microsoft/Edge/Application/msedge.exe');
    if (fs.existsSync(edge)) spawn(edge,[file],{windowsHide:true,detached:true,stdio:'ignore'}).unref();
  }
  process.once('SIGINT',() => app.close().then(() => process.exit(0)));
  process.once('SIGTERM',() => app.close().then(() => process.exit(0)));
}
module.exports = {start,validate,rendererVersion};
if (require.main === module) main().catch(error => {console.error(error.message);process.exitCode = 1;});
