import { createVideoRenderer } from './vendor/src/lib/video/VideoRenderer';
import { OfflineSpectrum } from './vendor/src/lib/video/OfflineSpectrum';
import { convertGeminiJson, isGeminiWordJson } from './vendor/src/lib/geminiImport';
import { parseTranscript } from './vendor/src/lib/parser';
import type { MusicLyricTheme, Segment } from './vendor/src/types';

declare global {
  interface Window { NativeFrames?: { postMessage(data: ArrayBuffer): void; onmessage: ((event: MessageEvent<string>) => void) | null }; Native: { send(id: string, action: string, json: string): void; state(): string; frame(id: string, index: number, base64: string): string; visualActive(active: boolean): void };
    nativeReply: (id: string, result: any, error?: string) => void; nativeProgress: (text: string) => void; nativeCancelled: () => void;
  }
}
interface Song { id: string; title: string; duration: number; rate: number; codec: string; channels: number; theme: MusicLyricTheme; segments: Segment[]; cueCount?: number; lyrics: string; guide: string; profile?: { width: number; height: number; fps: number; mode: string; audio: string; encoder?: string } }
type Renderer = Awaited<ReturnType<typeof createVideoRenderer>>;
const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const value = (id: string) => $(id) as HTMLInputElement;
const pending = new Map<string, { resolve: (value: any) => void; reject: (error: Error) => void }>();
let sequence = 0;
function native(action: string, data: object = {}): Promise<any> {
  return new Promise((resolve, reject) => { const id = String(++sequence); pending.set(id, { resolve, reject }); window.Native.send(id, action, JSON.stringify(data)); });
}
window.nativeReply = (id, data, error) => { const call = pending.get(id); if (!call) return; pending.delete(id); error ? call.reject(new Error(error)) : call.resolve(data); };
const status = (text: string, error = false) => { $('status').textContent = text; $('status').classList.toggle('error', error); };
window.nativeProgress = text => status(text);
let cancelled = false;
window.nativeCancelled = () => { cancelled = true; };
const sleep = () => new Promise(resolve => setTimeout(resolve, 0));
const clock = (seconds: number) => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
let song: Song | undefined, samples: Float32Array | undefined, renderer: Renderer | undefined, spectrum: OfflineSpectrum | undefined;
let frame = -1, rendering = false, exporting = false, loading = false, panel = 'library', lastError = '', generation = 0;
let screenAwake = false;
let scrubbing = false;
let pcPending = false, pcPolling = false;
const config = () => { const [width, height] = value('size').value.split('x').map(Number); return { width, height, fps: Number(value('fps').value) }; };
const playback = (): { id: string; playing: boolean; seconds: number; error: string } => JSON.parse(window.Native.state());
function show(id: string) { panel = id; for (const name of ['library', 'listen', 'lyrics', 'export']) $(name).hidden = name !== id; window.scrollTo(0, 0); }
function event(id: string, task: () => void | Promise<void>) { $(id).addEventListener('click', () => Promise.resolve().then(task).catch(error => status(error.message, true))); }
function busyImport(busy: boolean) { loading = busy; for (const id of ['capture', 'latest', 'importMedia', 'libraryButton']) ($(id) as HTMLButtonElement).disabled = busy; }
async function list() {
  const result = await native('list'); $('songs').replaceChildren();
  value('volume').value = String(result.volume);
  if (result.pc) applyPc(result.pc);
  if (!result.songs.length) { const p = document.createElement('p'); p.className = 'hint'; p.textContent = 'Your imported songs will appear here.'; $('songs').append(p); }
  for (const item of result.songs as Song[]) {
    const row = document.createElement('div'); row.className = 'song';
    const open = document.createElement('button'); open.className = 'open'; open.textContent = item.title;
    const detail = document.createElement('small'); detail.textContent = `${clock(item.duration)} · ${item.codec} · ${item.cueCount ? 'timed lyrics' : 'instrumental visuals'}`; open.append(detail);
    open.onclick = () => select(item).catch(error => status(error.message, true));
    const remove = document.createElement('button'); remove.className = 'remove'; remove.textContent = 'Remove';
    remove.onclick = async () => {
      if (loading || exporting || !confirm('Remove this song from the app library? Your original file and exported videos will remain.')) return;
      try { await native('delete', { id: item.id }); if (song?.id === item.id) { song = undefined; samples = undefined; renderer?.destroy(); renderer = undefined; } await list(); }
      catch (error) { status((error as Error).message, true); }
    };
    row.append(open, remove); $('songs').append(row);
  }
  return result;
}
async function select(item: Song) {
  if (loading || exporting) return;
  loading = true; const version = ++generation;
  try {
    status('Loading visualization…');
    if (song?.id !== item.id || !samples) {
      const response = await fetch(`/analysis/${encodeURIComponent(item.id)}`);
      if (!response.ok) throw new Error('This song’s analysis is missing. Import it again.');
      samples = new Float32Array(await response.arrayBuffer());
    }
    if (version !== generation) return;
    song = await native('select', { id: item.id });
    if (song!.profile) {
      const p = song!.profile;
      value('size').value = `${p.width}x${p.height}`; value('fps').value = String(p.fps);
      value('videoQuality').value = p.mode; value('audioQuality').value = p.audio;
      value('phoneEncoder').value = p.encoder || 'auto';
    }
    await native('load', { id: item.id });
    value('theme').value = song!.theme; $('songTitle').textContent = song!.title;
    value('seek').max = String(song!.duration); $('duration').textContent = clock(song!.duration);
    await reset(); show('listen'); status(song!.segments.length ? '' : 'Add Gemini JSON with Lyrics, or listen with instrumental visuals.');
  } finally { loading = false; }
}
async function reset() {
  renderer?.destroy(); renderer = undefined; frame = -1;
  if (!song || !samples) return;
  spectrum = new OfflineSpectrum([samples], 44100);
  renderer = await createVideoRenderer({ ...config(), theme: song.theme, title: song.title, segments: song.segments, duration: song.duration });
  $('stage').replaceChildren(renderer.canvas); renderer.draw(0, spectrum); frame = 0;
}
async function drawTo(target: number) {
  if (!renderer || !spectrum || !song) return;
  if (target < frame) await reset();
  const current = renderer, analysis = spectrum, fps = config().fps;
  while (frame < target && current === renderer && !exporting) {
    current!.draw(++frame / fps, analysis!);
    if (frame % 8 === 0) await sleep();
  }
}
async function tick() {
  const active = panel === 'listen' && playback().playing;
  if (active !== screenAwake) { screenAwake = active; window.Native.visualActive(active); }
  if (song && panel === 'listen' && !loading && !rendering && !exporting) {
    const state = playback();
    if (state.error && state.error !== lastError) { lastError = state.error; status(state.error, true); }
    if (state.id === song.id) {
      if (!scrubbing) { value('seek').value = String(state.seconds); $('position').textContent = clock(state.seconds); }
      $('play').textContent = state.playing ? 'Pause' : 'Play';
      rendering = true;
      try { await drawTo(Math.min(Math.ceil(song.duration * config().fps) - 1, Math.floor(state.seconds * config().fps))); }
      catch (error) { status((error as Error).message, true); }
      finally { rendering = false; }
    }
  }
  requestAnimationFrame(tick);
}
async function importSong(action: string) {
  if (loading || exporting) return; busyImport(true); status('Choose your original audio or captured master video.');
  let imported: Song | undefined;
  try { imported = await native(action); await list(); }
  finally { busyImport(false); }
  if (imported) await select(imported);
}
event('back', () => native('back'));
event('libraryButton', async () => { if (exporting) return; show('library'); await list(); });
event('capture', () => native('capture'));
event('latest', () => importSong('latest'));
event('importMedia', () => importSong('pickMedia'));
event('play', async () => { if (!song || loading) return; const state = playback(); await native(state.playing && state.id === song.id ? 'pause' : 'play', { id: song.id }); });
value('volume').addEventListener('input', () => native('volume', { value: Number(value('volume').value) }).catch(error => status(error.message, true)));
value('seek').addEventListener('pointerdown', () => { scrubbing = true; });
window.addEventListener('pointerup', () => { setTimeout(() => { scrubbing = false; }, 0); });
value('seek').addEventListener('pointercancel', () => { scrubbing = false; });
value('seek').addEventListener('input', () => { $('position').textContent = clock(Number(value('seek').value)); });
value('seek').addEventListener('change', async () => {
  scrubbing = false;
  if (!song || loading || exporting) return;
  const seconds = Number(value('seek').value), resume = playback().playing;
  loading = true;
  try {
    await native('pause', { id: song.id }); await native('seek', { id: song.id, seconds });
    status('Syncing visualization…'); await drawTo(Math.floor(seconds * config().fps));
    if (resume) await native('play', { id: song.id }); status('');
  } catch (error) { status((error as Error).message, true); }
  finally { loading = false; }
});
value('theme').addEventListener('change', async () => {
  if (!song || exporting || loading) return;
  loading = true;
  try { song.theme = value('theme').value as MusicLyricTheme; await native('save', { id: song.id, theme: song.theme }); await reset(); }
  catch (error) { status((error as Error).message, true); } finally { loading = false; }
});
event('editLyrics', async () => {
  if (!song) return; await native('pause', { id: song.id });
  value('titleInput').value = song.title; value('jsonInput').value = song.lyrics; value('guideInput').value = song.guide;
  status(''); show('lyrics');
});
event('copyPrompt', async () => { await native('prompt'); status('Gemini prompt copied. Attach the matching MP4 in Gemini.'); });
event('shareGemini', () => native('shareGemini'));
event('pickLyrics', async () => { value('jsonInput').value = (await native('pickLyrics')).text; status('JSON loaded. Save & preview to check it.'); });
event('saveLyrics', async () => {
  if (!song) return;
  const text = value('jsonInput').value.trim(), guide = value('guideInput').value;
  if (text.length > 1500000) throw new Error('Import JSON under 1.5 MB.');
  const title = value('titleInput').value.trim() || song.title;
  let segments: Segment[] = [];
  let review = '';
  if (text) {
    if (isGeminiWordJson(text)) {
      const result = convertGeminiJson(text, { title, lyrics: guide, duration: song.duration });
      segments = parseTranscript(JSON.stringify(result), 'json').segments;
      review = `${result.reviewNotes.uncertainEntries} words marked uncertain by Gemini. Review the preview.`;
    } else {
      if (!/^[\[{`]/.test(text)) throw new Error('Paste timed JSON, not plain lyrics.');
      segments = parseTranscript(text, 'json').segments;
    }
    if (!segments.length || segments.some(s => !Number.isFinite(s.start) || !Number.isFinite(s.end) || s.start < 0 || s.end <= s.start || s.end > song!.duration + .05))
      throw new Error('Lyrics need valid timings within this song. Use the matching Gemini JSON.');
  }
  song = await native('save', { id: song.id, title, lyrics: text, guide, segments });
  $('songTitle').textContent = song!.title; await reset(); show('listen'); status(review || (text ? 'Lyrics saved.' : 'Saved with instrumental visuals.'));
});
event('closeLyrics', () => show('listen'));
event('showExport', async () => {
  if (!song) return; await native('pause', { id: song.id }); show('export'); status('');
  await refreshPc();
  $('audioInfo').textContent = `Source: ${song.codec} · ${song.rate.toLocaleString()} Hz · ${song.channels} channels. Preserved PCM/FLAC audio exports as MKV; AAC can use MP4. Video settings never lower preserved audio quality.`;
});
event('closeExport', async () => { if (exporting) return; await reset(); show('listen'); });
event('cancelExport', async () => { cancelled = true; await native('cancel'); });
event('shareExport', () => native('shareExport'));
function applyPc(info: any) {
  value('exportTarget').value = info.target;
  $('pcInfo').textContent = info.paired ? `Paired with ${info.name}. Automatic checks availability before sending.` : 'No PC paired. Automatic exports on this phone until you pair one.';
  pcPending = info.pending;
  $('pcJob').hidden = !pcPending;
  ($('startExport') as HTMLButtonElement).disabled = pcPending;
}
async function refreshPc() {
  applyPc(await native('pcSettings'));
  if (pcPending) await checkPc();
}
async function checkPc() {
  if (pcPolling || !pcPending || exporting) return;
  pcPolling = true;
  try {
    const job = await native('pcStatus');
    const descriptions: Record<string, string> = {
      uploading: 'Upload paused. Resume to send the original media and analysis.', queued: 'Waiting for the PC to finish its current export.',
      rendering: `Rendering on PC · ${Math.floor(job.frames / job.total * 100)}% · ${job.frames} / ${job.total} frames`,
      complete: 'Ready. Save the finished video to this phone.', failed: job.error || 'The PC export failed. Remove this job and try again.', cancelled: 'PC export cancelled.',
    };
    $('pcProgress').textContent = `${job.title} · ${descriptions[job.status] || job.status}`;
    $('pcResume').hidden = job.status !== 'uploading'; $('pcDownload').hidden = job.status !== 'complete';
  } catch (error) {
    $('pcProgress').textContent = `PC unavailable: ${(error as Error).message}. Your pending job is saved. Reconnect and check progress.`;
    $('pcDownload').hidden = true; $('pcResume').hidden = false;
  } finally { pcPolling = false; }
}
value('exportTarget').addEventListener('change', () => native('pcTarget', { target: value('exportTarget').value }).catch(error => status(error.message, true)));
event('pairPc', async () => {
  status('Connecting to PC…'); applyPc(await native('pcPair', { code: value('pairCode').value }));
  value('pairCode').value = ''; ($('pairOptions') as HTMLDetailsElement).open = false; status('PC paired. Automatic export can now use it.');
});
event('forgetPc', async () => { applyPc(await native('pcForget')); status('PC pairing removed.'); });
event('pcRefresh', checkPc);
event('pcRemove', async () => { if (exporting) return; await native('pcRemove'); await refreshPc(); status('PC export removed.'); });
function exportBusy(busy: boolean) {
  exporting = busy;
  for (const id of ['exportOptions', 'startExport', 'shareExport', 'closeExport']) $(id).hidden = busy;
  $('cancelExport').hidden = !busy; ($('libraryButton') as HTMLButtonElement).disabled = busy;
  for (const id of ['pcRefresh', 'pcResume', 'pcDownload', 'pcRemove']) ($ (id) as HTMLButtonElement).disabled = busy;
}
async function transferPc(action: 'pcResume' | 'pcDownload') {
  if (exporting) return;
  exportBusy(true); cancelled = false; $('exportProgress').textContent = action === 'pcDownload' ? 'Saving from PC…' : 'Resuming upload…';
  $('cancelExport').textContent = 'Pause transfer'; $('exportStats').hidden = true;
  try {
    const result = await native(action);
    $('exportProgress').textContent = action === 'pcDownload' ? `Saved ${result.extension.toUpperCase()} in Download/NoFocus.` : 'Upload finished. You can leave this screen while the PC renders.';
    status(action === 'pcDownload' ? 'Video saved from PC.' : 'Rendering on paired PC.');
  } catch (error) { status((error as Error).message, true); }
  finally { exportBusy(false); await refreshPc(); }
}
event('pcResume', () => transferPc('pcResume'));
event('pcDownload', () => transferPc('pcDownload'));
setInterval(() => { if (panel === 'export' && pcPending && !exporting && !document.hidden) void checkPc(); }, 2500);
function binaryFrame(packet: Uint8Array): Promise<void> {
  return new Promise((resolve, reject) => {
    const bridge = window.NativeFrames!;
    const timeout = setTimeout(() => { bridge.onmessage = null; reject(new Error('Frame transfer timed out.')); }, 60000);
    bridge.onmessage = event => { clearTimeout(timeout); bridge.onmessage = null; event.data ? reject(new Error(event.data)) : resolve(); };
    try { bridge.postMessage(packet.buffer as ArrayBuffer); } catch (error) { clearTimeout(timeout); bridge.onmessage = null; reject(error); }
  });
}
event('startExport', async () => {
  if (!song || !samples || exporting || pcPending) return;
  exportBusy(true); cancelled = false;
  $('cancelExport').textContent = 'Cancel export';
  $('exportStats').hidden = true;
  $('exportProgress').textContent = 'Choosing export destination…'; $('progress').hidden = false;
  const progress = $('progress') as HTMLProgressElement; progress.value = 0;
  let output: Renderer | undefined;
  try {
    const settings = config();
    const options = { id: song.id, ...settings, mode: value('videoQuality').value, audio: value('audioQuality').value, encoder: value('phoneEncoder').value };
    const target = value('exportTarget').value;
    await native('pcTarget', { target });
    const pc = target === 'phone' ? { available: false } : await native('pcAvailable');
    if (cancelled) throw new Error('Export cancelled.');
    if (target === 'pc' && !pc.available) throw new Error(`PC unavailable: ${pc.reason}. Start the companion and connect to the same Wi-Fi.`);
    if (pc.available) {
      $('cancelExport').textContent = 'Pause transfer';
      $('exportProgress').textContent = 'Sending source to paired PC…';
      await native('pcBegin', options);
      $('exportProgress').textContent = 'Upload finished. You can leave this screen while the PC renders.';
      status('Rendering on paired PC. Return to Export to save the finished video.');
      return;
    }
    status(target === 'auto' ? `Using this phone. ${pc.reason || ''}` : 'Rendering on this phone.');
    const analysis = new OfflineSpectrum([samples], 44100);
    output = await createVideoRenderer({ ...settings, theme: song.theme, title: song.title, segments: song.segments, duration: song.duration });
    const job = await native('beginExport', { ...options, raw: !!window.NativeFrames });
    const began = performance.now(); let drawMs = 0, readMs = 0, sendMs = 0, lastProgress = 0;
    const packet = job.raw ? new Uint8Array(settings.width * settings.height * 4 + 40) : undefined;
    if (packet) packet.set(new TextEncoder().encode(job.id), 0);
    for (let index = 0; index < job.total; index++) {
      if (cancelled) throw new Error('Export cancelled.');
      const drawing = performance.now(); output.draw(index / settings.fps, analysis); drawMs += performance.now() - drawing;
      const reading = performance.now();
      let png = '';
      if (packet) { new DataView(packet.buffer).setUint32(36, index, true); packet.set(output.pixels(), 40); }
      else png = output.canvas.toDataURL('image/png').split(',')[1];
      readMs += performance.now() - reading;
      const sending = performance.now();
      if (packet) await binaryFrame(packet);
      else { const error = window.Native.frame(job.id, index, png); if (error) throw new Error(error); }
      sendMs += performance.now() - sending;
      if (performance.now() - lastProgress > 200 || index + 1 === job.total) {
        progress.value = (index + 1) / job.total;
        const elapsed = (performance.now() - began) / 1000, remaining = elapsed / (index + 1) * (job.total - index - 1);
        $('exportProgress').textContent = `${job.encoder} · ${Math.floor(progress.value * 100)}% · ${clock(elapsed)} elapsed · about ${clock(remaining)} left`;
        lastProgress = performance.now(); await sleep();
      }
    }
    $('exportProgress').textContent = 'Saving video…';
    const result = await native('finishExport', { id: job.id });
    $('exportProgress').textContent = `Saved ${result.extension.toUpperCase()} in Download/NoFocus. ${job.encoder} · ${clock(result.elapsedMs / 1000)}.`;
    $('exportStatsText').textContent = `Per frame: draw ${Math.round(drawMs / job.total)} ms · read pixels ${Math.round(readMs / job.total)} ms · transfer/encoder wait ${Math.round(sendMs / job.total)} ms. `
      + (drawMs > sendMs ? 'Drawing takes most of the time. A smaller canvas or paired PC can help.' : 'Encoding and transfer take most of the time. Try hardware H.264 or a paired PC.');
    $('exportStats').hidden = false;
    status('Video exported. Your original audio was ' + (value('audioQuality').value === 'preserve' ? 'preserved without re-encoding.' : 'encoded as AAC 320 kbps.'));
  } catch (error) { await native('cancel'); $('exportProgress').textContent = ''; status((error as Error).message, true); }
  finally { output?.destroy(); exportBusy(false); await refreshPc(); }
});
list().then(async result => {
  if (result.latest) await importSong('latest');
  else { const selected = result.songs.find((item: Song) => item.id === (playback().id || result.selected)); if (selected) await select(selected); }
}).catch(error => status(error.message, true));
requestAnimationFrame(tick);
