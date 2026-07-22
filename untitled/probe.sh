#!/bin/bash
echo "=== onebot11 config ==="
cat /opt/QQ/resources/app/app_launcher/napcat/config/onebot11_356289140.json 2>&1
echo ""
echo "=== try with token ==="
TOKEN=$(cat /opt/QQ/resources/app/app_launcher/napcat/config/onebot11_356289140.json | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))')
echo "token prefix: ${TOKEN:0:10}..."

cat > /tmp/check_ws2.py << PYEOF
import asyncio, json, websockets, sys
TOKEN = open("/tmp/tk").read().strip()
async def main():
    uri = f"ws://127.0.0.1:5701?access_token={TOKEN}"
    try:
        async with websockets.connect(uri, open_timeout=5) as ws:
            for action in ['get_login_info', 'get_status']:
                await ws.send(json.dumps({'action':action,'params':{},'echo':action}))
                r = await asyncio.wait_for(ws.recv(), timeout=5)
                print(action.upper()+':', r[:600])
    except Exception as e:
        print('ERR:', type(e).__name__, e)
asyncio.run(main())
PYEOF
echo "$TOKEN" > /tmp/tk
python3 /tmp/check_ws2.py 2>&1
