import asyncio, json, websockets, time
from urllib.parse import quote
TOKEN = '1-2VZwUL2;LvIXni'

async def main():
    uri = 'ws://127.0.0.1:5701?access_token=' + quote(TOKEN)
    async with websockets.connect(uri, open_timeout=5) as ws:
        await ws.send(json.dumps({'action':'get_group_list','params':{},'echo':'gl'}))
        await ws.send(json.dumps({
            'action':'send_group_msg',
            'params':{'group_id':437625485,'message':[{'type':'text','data':{'text':'[bot self-test] heartbeat check - please ignore'}}]},
            'echo':'sgm'
        }))
        end = asyncio.get_event_loop().time() + 8
        while asyncio.get_event_loop().time() < end:
            try:
                r = await asyncio.wait_for(ws.recv(), timeout=2)
                d = json.loads(r)
                if d.get('post_type'):
                    continue
                echo = d.get('echo')
                if echo == 'gl':
                    data = d.get('data') or []
                    print('GROUPS:')
                    for g in data:
                        print('  ' + str(g.get('group_id')) + ' ' + str(g.get('group_name')))
                elif echo == 'sgm':
                    print('SEND_RESULT: retcode=' + str(d.get('retcode')) + ' status=' + str(d.get('status')) + ' data=' + str(d.get('data'))[:200])
                    if d.get('retcode') == 0:
                        print('=> message sent, waiting 3s for candybear to receive...')
                        await asyncio.sleep(3)
            except asyncio.TimeoutError:
                continue

asyncio.run(main())
