const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const os = require('node:os');
const https = require('node:https');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const {start,rendererVersion} = require('./server.cjs');
const ffmpeg = require('ffmpeg-static');
function request(pair,method,route,data,headers={}) {
  return new Promise((resolve,reject) => {
    const payload = data == null ? null : Buffer.isBuffer(data) ? data : Buffer.from(JSON.stringify(data));
    const req = https.request(pair.url + route,{method,rejectUnauthorized:false,headers:{Authorization:'Bearer ' + pair.token,...(payload ? {'Content-Length':payload.length} : {}),...headers}},res => {
      const chunks=[];res.on('data',chunk=>chunks.push(chunk));res.on('end',()=>{const bytes=Buffer.concat(chunks);resolve({status:res.statusCode,headers:res.headers,bytes,json:() => JSON.parse(bytes)});});res.on('error',reject);
    });
    req.on('error',reject);req.end(payload);
  });
}
async function wait(pair,id) {
  const deadline=Date.now()+120000;
  while(Date.now()<deadline) {
    const job=(await request(pair,'GET','/v1/jobs/'+id)).json();
    if (job.status==='failed') throw new Error(job.error);
    if (job.status==='complete') return job;
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw new Error('PC rendering timed out');
}
test('unified pairing persists both directions, uses observed addresses and protects desktop controls', async () => {
  const {audioCodes,localIpv4} = require('./desktop.cjs');
  const state = await fsp.mkdtemp(path.join(os.tmpdir(),'nofocus-pair-test-'));
  let app;
  try {
    app = await start({state,port:0,host:'127.0.0.1'});let pair = app.pairing('127.0.0.1');
    assert.equal((await request(pair,'GET','/v1/info')).json().desktopProtocol,1);
    const details = {id:crypto.randomUUID(),name:'Test phone',address:'10.9.8.7'};
    assert.equal((await request({...pair,token:'wrong'},'POST','/v1/connect',details)).status,401);
    assert.equal((await request(pair,'POST','/v1/connect',details,{Origin:'https://example.test'})).status,401);
    assert.equal((await request(pair,'POST','/v1/connect',{...details,name:'x'.repeat(101)})).status,400);
    const audio = (await request(pair,'POST','/v1/connect',details)).json().audio;
    assert.match(audio.toPhone,/^[A-Z2-7]{16}$/);assert.match(audio.toPc,/^[A-Z2-7]{16}$/);
    assert.notEqual(audio.toPhone,audio.toPc);assert.deepEqual(audio,audioCodes(pair.token));
    assert.notDeepEqual(audio,audioCodes('00'.repeat(32)));
    const desktop = (await request(pair,'GET','/v1/desktop')).json();
    assert.equal(desktop.phone.address,'127.0.0.1');assert.equal(desktop.phone.name,details.name);
    assert.equal((await request({...pair,token:'bad'},'GET','/v1/desktop/pairings')).status,401);
    assert.ok(localIpv4('192.168.1.255'));assert.ok(!localIpv4('8.8.8.8'));assert.ok(!localIpv4('10.300.2.3'));
    await app.close();app = await start({state,port:0,host:'127.0.0.1'});pair = app.pairing('127.0.0.1');
    assert.deepEqual((await request(pair,'GET','/v1/desktop')).json(),desktop);
    await app.close();app = null;
    await fsp.unlink(path.join(state,'identity.json'));
    app = await start({state,port:0,host:'127.0.0.1'});pair = app.pairing('127.0.0.1');
    const rotated = (await request(pair,'GET','/v1/desktop')).json();
    assert.equal(rotated.phone,null,'revoking the identity must forget the old phone');
    assert.notDeepEqual(rotated.audio,audio);
    await request(pair,'POST','/v1/connect',details);
    await app.close();app = await start({state,port:0,host:'127.0.0.1'});pair = app.pairing('127.0.0.1');
    assert.equal((await request(pair,'GET','/v1/desktop')).json().phone.name,details.name,'new pairing survives restart');
  } finally {if(app)await app.close();await fsp.rm(state,{recursive:true,force:true});}
});
test('authenticated PC rendering, exact audio/RGB, hardware parity, queue recovery and cancellation', {timeout:180000}, async t => {
  const state=await fsp.mkdtemp(path.join(os.tmpdir(),'nofocus-export-test-'));
  let app;
  try {
    const source=path.join(state,'source.wav'), pcm=Buffer.alloc(48000*8), analysis=Buffer.alloc(44100*4);
    for(let n=0;n<48000;n++){pcm.writeFloatLE(Math.sin(n*2*Math.PI*440/48000)*.12345678,n*8);pcm.writeFloatLE(Math.sin(n*2*Math.PI*880/48000)*.23456789,n*8+4);}
    execFileSync(ffmpeg,['-v','error','-f','f32le','-ar','48000','-ac','2','-i','pipe:0','-c:a','pcm_f32le',source],{input:pcm,windowsHide:true});
    app=await start({state,port:0,host:'127.0.0.1'});let pair=app.pairing('127.0.0.1');
    assert.equal((await request({...pair,token:'bad'},'GET','/v1/info')).status,401);
    assert.equal((await request(pair,'GET','/v1/info',null,{Origin:'https://evil.test'})).status,401);
    // Canonical Segment/MusicLyricWord shape emitted by the phone's Gemini parser.
    const segments=[
      {id:'intro',start:0,end:.4,primary:'',translation:'Gather',secondary:'',words:[],translationWords:[{value:'Gather',start:0,end:.4}],translationTiming:'sung'},
      {id:'verse',start:.4,end:.95,primary:'Hola',translation:'Hello',secondary:'',words:[{value:'Hola',start:.4,end:.6}],translationWords:[{value:'Hello',start:.6,end:.95}],translationTiming:'sung'},
    ];
    const config={id:crypto.randomUUID(),rendererVersion,width:320,height:320,fps:24,duration:1,theme:'sketchbook',mode:'lossless',audio:'preserve',title:'PC test',codec:'pcm_f32le',segments,sourceBytes:fs.statSync(source).size,analysisBytes:analysis.length};
    assert.equal((await request(pair,'POST','/v1/jobs',{...config,width:99999})).status,400);
    assert.equal((await request(pair,'POST','/v1/jobs',{...config,rendererVersion:'old'})).status,400);
    assert.equal((await request(pair,'POST','/v1/jobs',{...config,segments:[{...segments[1],words:[{value:'Hola',start:.4,end:.4}]}]})).status,400);
    assert.equal((await request(pair,'POST','/v1/jobs',{...config,segments:[{...segments[1],words:[null]}]})).status,400);
    assert.equal((await request(pair,'POST','/v1/jobs',config)).status,201);
    assert.equal((await request(pair,'POST','/v1/jobs',config)).status,200);
    assert.equal((await request(pair,'POST','/v1/jobs',{...config,title:'conflict'})).status,409);
    assert.equal((await request(pair,'PUT',`/v1/jobs/${config.id}/source`,Buffer.from('short'))).status,409);
    assert.equal((await request(pair,'PUT',`/v1/jobs/${config.id}/source`,await fsp.readFile(source))).status,200);
    assert.equal((await request(pair,'PUT',`/v1/jobs/${config.id}/analysis`,analysis)).status,200);
    await request(pair,'POST',`/v1/jobs/${config.id}/start`,{});await wait(pair,config.id);
    const download=await request(pair,'GET',`/v1/jobs/${config.id}/file`);assert.equal(download.status,200);
    assert.equal(crypto.createHash('sha256').update(download.bytes).digest('hex'),download.headers['x-content-sha256']);
    const output=path.join(state,'result.mkv');await fsp.writeFile(output,download.bytes);
    const audio=execFileSync(ffmpeg,['-v','error','-i',output,'-map','0:a:0','-f','f32le','pipe:1'],{windowsHide:true});
    assert.deepEqual(audio,pcm);
    const pixels=execFileSync(ffmpeg,['-v','error','-i',output,'-vf','select=eq(n\\,18)','-frames:v','1','-pix_fmt','rgba','-f','rawvideo','pipe:1'],{windowsHide:true,maxBuffer:5*1024*1024});
    // Compare an active lyric frame after sequential state updates, not just the empty intro.
    let expectedPixels;
    const {chromium}=require('playwright');const browser=await chromium.launch({channel:'msedge',headless:true});
    try {
      const page=await browser.newPage();
      const bundle=(await require('esbuild').build({stdin:{contents:"export {createVideoRenderer} from './visualizer/vendor/src/lib/video/VideoRenderer';export {OfflineSpectrum} from './visualizer/vendor/src/lib/video/OfflineSpectrum';",resolveDir:path.resolve(__dirname,'..')},bundle:true,format:'iife',globalName:'Parity',write:false})).outputFiles[0].text;
      const font=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/visualizer/fonts/caveat.ttf')).toString('base64');
      await page.setContent(`<style>@font-face{font-family:Caveat;src:url(data:font/ttf;base64,${font});font-weight:100 900}</style>`);await page.addScriptTag({content:bundle});
      const expected=await page.evaluate(async config=>{const r=await Parity.createVideoRenderer(config);const spectrum=new Parity.OfflineSpectrum([new Float32Array(44100)],44100);for(let i=0;i<=18;i++)r.draw(i/config.fps,spectrum);const bytes=Array.from(r.pixels());r.destroy();return bytes;},config);
      expectedPixels=Buffer.from(expected);assert.deepEqual(pixels,expectedPixels);
    } finally {await browser.close();}
    await app.close();app=await start({state,port:0,host:'127.0.0.1'});pair=app.pairing('127.0.0.1');
    assert.equal((await request(pair,'GET',`/v1/jobs/${config.id}`)).json().status,'complete');
    assert.deepEqual((await request(pair,'GET',`/v1/jobs/${config.id}/file`)).bytes,download.bytes);
    assert.equal((await request(pair,'DELETE',`/v1/jobs/${config.id}`)).status,200);
    assert.equal((await request(pair,'GET',`/v1/jobs/${config.id}`)).status,404);
    // High-quality hardware mode keeps the canvas composition, frame count,
    // orientation and original PCM. Lossless above still uses exact RGB.
    const publish={...config,id:crypto.randomUUID(),mode:'publish'};
    await request(pair,'POST','/v1/jobs',publish);
    await request(pair,'PUT',`/v1/jobs/${publish.id}/source`,await fsp.readFile(source));
    await request(pair,'PUT',`/v1/jobs/${publish.id}/analysis`,analysis);
    await request(pair,'POST',`/v1/jobs/${publish.id}/start`,{});
    const completed=await wait(pair,publish.id);t.diagnostic(completed.encoder);
    const published=path.join(state,'publish.mkv');await fsp.writeFile(published,(await request(pair,'GET',`/v1/jobs/${publish.id}/file`)).bytes);
    const decoded=execFileSync(ffmpeg,['-v','error','-i',published,'-map','0:v:0','-fps_mode','passthrough','-pix_fmt','rgba','-f','rawvideo','pipe:1'],{windowsHide:true,maxBuffer:20*1024*1024});
    assert.equal(decoded.length,320*320*4*24,'no dropped, duplicated or extra encoder frames');
    const actualFrame=decoded.subarray(18*320*320*4,19*320*320*4);
    let difference=0;for(let i=0;i<actualFrame.length;i++) difference+=Math.abs(actualFrame[i]-expectedPixels[i]);
    assert.ok(difference/actualFrame.length<6,`same active lyric frame with H.264 compression: mean error ${difference/actualFrame.length}`);
    assert.deepEqual(execFileSync(ffmpeg,['-v','error','-i',published,'-map','0:a:0','-f','f32le','pipe:1'],{windowsHide:true}),pcm);
    await request(pair,'DELETE',`/v1/jobs/${publish.id}`);
    const cancel={...config,id:crypto.randomUUID(),duration:30,analysisBytes:30*44100*4};
    await request(pair,'POST','/v1/jobs',cancel);
    await request(pair,'PUT',`/v1/jobs/${cancel.id}/source`,await fsp.readFile(source));
    await request(pair,'PUT',`/v1/jobs/${cancel.id}/analysis`,Buffer.alloc(cancel.analysisBytes));
    await request(pair,'POST',`/v1/jobs/${cancel.id}/start`,{});
    // Pause the active render, recreate the service, then resume using the
    // uploaded files. A second job must be accepted while the first is pending.
    assert.equal((await request(pair,'POST',`/v1/jobs/${cancel.id}/pause`,{})).json().status,'paused');
    assert.equal(fs.statSync(path.join(state,'jobs',cancel.id,'source')).size,config.sourceBytes);
    await app.close();app=await start({state,port:0,host:'127.0.0.1'});pair=app.pairing('127.0.0.1');
    assert.equal((await request(pair,'GET',`/v1/jobs/${cancel.id}`)).json().status,'paused');
    const next={...config,id:crypto.randomUUID()};
    assert.equal((await request(pair,'POST','/v1/jobs',next)).status,201);
    await request(pair,'PUT',`/v1/jobs/${next.id}/source`,await fsp.readFile(source));
    await request(pair,'PUT',`/v1/jobs/${next.id}/analysis`,analysis);
    await request(pair,'POST',`/v1/jobs/${cancel.id}/start`,{});
    await request(pair,'POST',`/v1/jobs/${next.id}/start`,{});
    assert.equal((await request(pair,'GET',`/v1/jobs/${next.id}`)).json().status,'queued');
    assert.equal((await request(pair,'GET','/v1/jobs')).json().jobs.length,2);
    await app.close();app=await start({state,port:0,host:'127.0.0.1'});pair=app.pairing('127.0.0.1');
    assert.equal((await request(pair,'GET',`/v1/jobs/${cancel.id}`)).json().status,'rendering');
    assert.equal((await request(pair,'DELETE',`/v1/jobs/${cancel.id}`)).status,200);
    assert.equal(fs.existsSync(path.join(state,'jobs',cancel.id)),false);
    await wait(pair,next.id);
    assert.equal((await request(pair,'DELETE',`/v1/jobs/${next.id}`)).status,200);
    const reservations=Array.from({length:8},()=>({...config,id:crypto.randomUUID()}));
    const created=await Promise.all(reservations.map(item=>request(pair,'POST','/v1/jobs',item)));
    assert.ok(created.every(response=>response.status===201),'concurrent queue reservations');
    assert.equal((await request(pair,'POST','/v1/jobs',{...config,id:crypto.randomUUID()})).status,429);
    assert.equal((await request(pair,'POST','/v1/jobs',reservations[0])).status,200,'retry existing id while queue is full');
    await Promise.all(reservations.map(item=>request(pair,'DELETE',`/v1/jobs/${item.id}`)));
  } finally {if(app) await app.close();await fsp.rm(state,{recursive:true,force:true});}
});
