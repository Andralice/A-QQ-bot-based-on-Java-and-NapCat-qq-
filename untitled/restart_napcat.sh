#!/bin/bash
# NapCat restart - kill QQ main process, wait, relaunch via alice's screen
set -e

echo "=== Step 1: Kill existing NapCat/QQ process ==="
QQ_PID=$(pgrep -f '/opt/QQ/qq.*356289140' | head -1)
if [ -n "$QQ_PID" ]; then
    echo "Killing QQ main process PID=$QQ_PID"
    kill -TERM $QQ_PID 2>&1 || true
    sleep 3
    # 如果还在就强杀
    if kill -0 $QQ_PID 2>/dev/null; then
        echo "Still alive, force killing"
        kill -KILL $QQ_PID 2>&1 || true
        sleep 2
    fi
else
    echo "QQ process not found, nothing to kill"
fi

# xvfb-run 父进程也会退出，screen session 也会死，等 8 秒让端口释放
echo ""
echo "=== Step 2: Wait 8s for port 5701 to be released ==="
sleep 8

echo ""
echo "=== Step 3: Check port 5701 ==="
if (echo > /dev/tcp/127.0.0.1/5701) 2>/dev/null; then
    echo "WARN: port 5701 still occupied, waiting 5 more seconds"
    sleep 5
else
    echo "OK: port 5701 is free"
fi

echo ""
echo "=== Step 4: Launch new NapCat in alice's screen ==="
su - alice -c 'screen -dmS napcat bash -c "xvfb-run -a /opt/QQ/qq --no-sandbox -q 356289140"' 2>&1
echo "Screen started, waiting 30s for QQ to boot..."

# 等 QQ 启动
for i in 1 2 3 4 5 6; do
    sleep 5
    PORT_OK=$( (echo > /dev/tcp/127.0.0.1/5701) 2>/dev/null && echo "yes" || echo "no" )
    echo "  ${i}x5s: port 5701 = $PORT_OK"
    if [ "$PORT_OK" = "yes" ]; then
        break
    fi
done

echo ""
echo "=== Step 5: Verify sendMsg works ==="
sleep 5
python3 /tmp/probe6.py 2>&1 | head -20

echo ""
echo "=== Done ==="
echo "NapCat screen session: su - alice -c 'screen -list'"
echo "Attach to see logs: su - alice -c 'screen -r napcat'"
