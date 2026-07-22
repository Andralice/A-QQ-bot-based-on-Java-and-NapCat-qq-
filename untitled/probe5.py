import asyncio, json, websockets
from urllib.parse import quote
TOKEN = '1-2VZwUL2;LvIXni'

async def main():
    uri = 'ws://127.0.0.1:5701?access_token=' + quote(TOKEN)
    async with websockets.connect(uri, open_timeout=5) as ws:
        tests = [
            ('send_group_msg', 'sgm1', {'group_id':437625485,'message':'ping1'}),
            ('send_group_msg', 'sgm2', {'group_id':437625485,'message':[{'type':'text','data':{'text':'ping2'}}]}),
            ('send_private_msg', 'spm', {'user_id':1548753512,'message':'ping-pm'}),
            ('set_group_ban', 'sgb', {'group_id':437625485,'user_id':0,'duration':0}),  # 测试其他 API
            ('get_version_info', 'gvi', {}),
        ]
        for action, echo, params in tests:
            await ws.send(json.dumps({'action':action,'params':params,'echo':echo}))
        end = asyncio.get_event_loop().time() + 6
        while asyncio.get_event_loop().time() < end:
            try:
                r = await asyncio.wait_for(ws.recv(), timeout=2)
                d = json.loads(r)
                if d.get('post_type'):
                    continue
                print(d.get('echo'), '->', 'retcode='+str(d.get('retcode')), 'status='+str(d.get('status')), 'msg='+str(d.get('message'))[:120])
            except asyncio.TimeoutError:
                continue

asyncio.run(main())
