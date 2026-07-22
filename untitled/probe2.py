import asyncio, json, websockets
from urllib.parse import quote
TOKEN = '1-2VZwUL2;LvIXni'

async def main():
    uri = 'ws://127.0.0.1:5701?access_token=' + quote(TOKEN)
    async with websockets.connect(uri, open_timeout=5) as ws:
        for action, echo in [('get_status', 'gs'), ('get_login_info', 'li')]:
            await ws.send(json.dumps({'action': action, 'params': {}, 'echo': echo}))
        end = asyncio.get_event_loop().time() + 6
        while asyncio.get_event_loop().time() < end:
            try:
                r = await asyncio.wait_for(ws.recv(), timeout=2)
                d = json.loads(r)
                if d.get('post_type') == 'meta_event':
                    s = d.get('status', {})
                    online = s.get('online')
                    good = s.get('good')
                    stat = d.get('stat')
                    print('HEARTBEAT: online=' + str(online) + ' good=' + str(good) + ' stat=' + str(stat))
                else:
                    print('API ' + str(d.get('echo')) + ' retcode=' + str(d.get('retcode')) + ' data=' + json.dumps(d.get('data'), ensure_ascii=False)[:400])
            except asyncio.TimeoutError:
                continue

asyncio.run(main())
