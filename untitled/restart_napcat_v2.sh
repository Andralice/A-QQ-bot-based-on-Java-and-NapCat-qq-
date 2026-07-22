#!/bin/bash
# NapCat hard restart - kill ALL QQ-related processes, clean session, relaunch
set -e

echo "=== Step 1: List all QQ-related processes ==="
pgrep -af 'xvfb|opt/QQ|/opt/QQ' | grep -v grep || echo "(none)"

echo ""
echo "=== Step 2: Kill all QQ processes (TERM first, then KILL after 5s) ==="
QQ_PIDS=$(pgrep -f 'opt/QQ/qq' || true)
if [ -n "$QQ_PIDS" ]; then
    echo "Killing (TERM): $QQ_PIDS"
    for p in $QQ_PIDS; do kill -TERM $p 2>&1 || true; done
    sleep 5
    # 再强杀
    REMAIN=$(pgrep -f 'opt/QQ/qq' || true)
    if [ -n "$REMAIN" ]; then
        echo "Still alive (KILL): $REMAIN"
        for p in $REMAIN; do kill -KILL $p 2>&1 || true; done
        sleep 3
    fi
fi
XVFB_PIDS=$(pgrep -f 'xvfb-run.*opt/QQ' || true)
if [ -n "$XVFB_PIDS" ]; then
    for p in $XVFB_PIDS; do kill -KILL $p 2>&1 || true; done
fi

echo ""
echo "=== Step 3: Wait 15s for port release and connection cleanup ==="
sleep 15

echo ""
echo "=== Step 4: Check port 5701 ==="
if (echo > /dev/tcp/127.0.0.1/5701) 2>/dev/null; then
    echo "WARN: port 5701 still occupied"
    ss -tlnp | grep 5701 || true
    sleep 5
else
    echo "OK: port 5701 free"
fi

echo ""
echo "=== Step 5: Verify all processes gone ==="
pgrep -af 'opt/QQ/qq|xvfb-run.*opt/QQ' | grep -v grep || echo "(none remaining)"

echo ""
echo "=== Step 6: Relaunch NapCat via alice's screen ==="
su - alice -c 'screen -dmS napcat bash -c "xvfb-run -a /opt/QQ/qq --no-sandbox -q 356289140"' 2>&1
echo "Relaunched, waiting 45s for QQ to fully boot and establish connection..."

for i in 1 2 3 4 5 6 7 8 9; do
    sleep 5
    PORT_OK=$( (echo > /dev/tcp/127.0.0.1/5701) 2>/dev/null && echo "yes" || echo "no" )
    QQ_OK=$(pgrep -f 'opt/QQ/qq.*356289140' | head -1 || echo "no")
    echo "  ${i}x5s: port 5701=$PORT_OK qq_pid=$QQ_OK"
    if [ "$PORT_OK" = "yes" ] && [ "$QQ_OK" != "no" ]; then
        # 再等 20 秒确保登录完成
        echo "  QQ process up, waiting 20s more for login to complete..."
        sleep 20
        break
    fi
done

echo ""
echo "=== Step 7: Test sendMsg ==="
python3 /tmp/probe6.py 2>&1 | head -20
