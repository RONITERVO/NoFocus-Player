// Test harness: its private pairing file is copied to app-private storage by the developer.
const fs = require('node:fs');
const path = require('node:path');
const {start} = require('./server.cjs');
(async () => {
  const state = path.resolve(__dirname,'../artifacts/pc-device-test');
  const app = await start({state,port:49633,host:'0.0.0.0'});
  const host = process.env.NOFOCUS_TEST_HOST || '127.0.0.1';
  const code = 'nofocus-pc-v1:' + Buffer.from(JSON.stringify(app.pairing(host))).toString('base64url');
  fs.writeFileSync(path.join(state,'pair.txt'),code,{mode:0o600});
  console.log('Device test companion ready on port 49633; pairing file written.');
  process.once('SIGINT',() => app.close().then(() => process.exit(0)));
})().catch(error => {console.error(error);process.exitCode = 1;});
