import asyncio, json, websockets
from urllib.parse import quote
TOKEN = '1-2VZwUL2;LvIXni'

async def main():
    uri = 'ws://127.0.0.1:5701?access_token=' + quote(TOKEN)
    async with websockets.connect(uri, open_timeout=5) as ws:
        # 试着用 text 类型发
        for i, payload in enumerate([
            {'action':'send_group_msg','params':{'group_id':437625485,'message':[{'type':'text','data':{'text':'.'}}]},'echo':'t1'},
            {'action':'send_group_msg','params':{'group_id':437625485,'message':'hello'},'echo':'t2'},
        ]):
            await ws.send(json.dumps(payload))
        end = asyncio.get_event_loop().time() + 5
        while asyncio.get_event_loop().time() < end:
            try:
                r = await asyncio.wait_for(ws.recv(), timeout=2)
                d = json.loads(r)
                if d.get('post_type'):
                    continue
                print('---', d.get('echo'), '---')
                print('retcode:', d.get('retcode'), 'status:', d.get('status'))
                print('message:', d.get('message'))
                print('wording:', d.get('wording'))
                print('data:', json.dumps(d.get('data'), ensure_ascii=False)[:500])
            except asyncio.TimeoutError:
                continue

asyncio.run(main())
