const ws = new WebSocket('ws://127.0.0.1:5701');
let count = 0;
ws.addEventListener('open', () => {
  ws.send(JSON.stringify({action:'get_login_info',params:{},echo:'1'}));
  ws.send(JSON.stringify({action:'get_status',params:{},echo:'2'}));
});
ws.addEventListener('message', e => {
  console.log('---');
  console.log(String(e.data).slice(0, 1000));
  count++;
  if (count >= 2) { setTimeout(() => process.exit(0), 500); }
});
ws.addEventListener('error', e => { console.log('ERR:', e.message || e); process.exit(1); });
setTimeout(() => { console.log('TIMEOUT'); process.exit(2); }, 5000);
