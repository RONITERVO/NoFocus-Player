// The same pinned HTTPS identity pairs exports and both audio directions.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const os = require('node:os');

function audioCodes(token) {
  const code = direction => {
    const bytes = crypto.createHmac('sha256', Buffer.from(token, 'hex')).update('NoFocus audio v1 ' + direction).digest().subarray(0, 10);
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
    let bits = 0, value = 0, result = '';
    for (const byte of bytes) { value = (value << 8) | byte; bits += 8; while (bits >= 5) { bits -= 5; result += alphabet[(value >>> bits) & 31]; } }
    return result;
  };
  return {toPhone: code('pc-to-phone'), toPc: code('phone-to-pc')};
}
function localIpv4(address) {
  if (typeof address !== 'string' || !/^\d{1,3}(\.\d{1,3}){3}$/.test(address)) return false;
  const p = address.split('.').map(Number);
  return p.every(n => n <= 255) && (p[0] === 10 || p[0] === 127 || (p[0] === 192 && p[1] === 168)
    || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 169 && p[1] === 254) || (p[0] === 100 && p[1] >= 64 && p[1] <= 127));
}
async function desktopControl(state, token) {
  const file = path.join(state, 'phone.json');
  let phone = null, saving = Promise.resolve();
  try { const saved = JSON.parse(await fs.readFile(file, 'utf8')); if (localIpv4(saved.address) && typeof saved.name === 'string') phone = saved; } catch {}
  const audio = audioCodes(token);
  return {
    audio,
    phone: () => phone,
    async connect(req, data) {
      const address = req.socket.remoteAddress.replace(/^::ffff:/, '');
      if (!localIpv4(address) || typeof data.name !== 'string' || !data.name.trim() || data.name.length > 100
        || typeof data.id !== 'string' || !/^[a-f0-9-]{36}$/.test(data.id)) throw new Error('Invalid phone details.');
      // Never trust a claimed address from request JSON: use the authenticated connection.
      const next = {address, name: data.name, id: data.id, updated: Date.now()};
      saving = saving.catch(() => {}).then(async () => {
        await fs.writeFile(file + '.tmp', JSON.stringify(next), {mode: 0o600});
        await fs.rename(file + '.tmp', file); phone = next;
      });
      await saving;
      return {audio, name: os.hostname()};
    },
  };
}
module.exports = {audioCodes, localIpv4, desktopControl};
