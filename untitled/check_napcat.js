const WebSocket = require('ws');
const ws = new WebSocket('ws://127.0.0.1:5701');
let count = 0;
ws.on('open', () => {
  ws.send(JSON.stringify({action:'get_login_info',params:{},echo:'1'}));
  ws.send(JSON.stringify({action:'get_status',params:{},echo:'2'}));
  ws.send(JSON.stringify({action:'get_friend_list',params:{},echo:'3'}));
});
ws.on('message', d => {
  console.log('---');
  console.log(d.toString().slice(0, 800));
  count++;
  if (count >= 3) { setTimeout(() => process.exit(0), 500); }
});
ws.on('error', e => { console.log('ERR:', e.message); process.exit(1); });
setTimeout(() => { console.log('TIMEOUT'); process.exit(2); }, 5000);
