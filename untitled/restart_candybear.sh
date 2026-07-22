#!/bin/bash
# Restart candybear java process in screen qq-bot
set -e

echo "=== Step 1: 找到 java 进程 ==="
JAVA_PID=$(pgrep -f 'untitled-1.0-SNAPSHOT.jar' | head -1)
SCREEN_PID=$(pgrep -f 'SCREEN.*qq-bot' | head -1)
echo "java pid=$JAVA_PID screen pid=$SCREEN_PID"

if [ -z "$JAVA_PID" ]; then
    echo "ERROR: java process not found"
    exit 1
fi

echo ""
echo "=== Step 2: 优雅停止 java（TERM）==="
# 先发 SIGTERM 让它走清理流程
kill -TERM $JAVA_PID
sleep 5
if kill -0 $JAVA_PID 2>/dev/null; then
    echo "Still alive, SIGKILL"
    kill -KILL $JAVA_PID
    sleep 2
fi
echo "java process gone"

# bash 父进程 1699286 也会自动退出，但 screen 1699284 还活着
echo ""
echo "=== Step 3: 检查 screen session ==="
screen -list 2>&1 | grep qq-bot || echo "screen qq-bot missing"

echo ""
echo "=== Step 4: 在原 screen session 里重启 java ==="
# 用 screen -S qq-bot -X exec 在里面跑命令
# 但 screen session 当前 detach 状态，需要用 screen -dmS 或向已存在的发命令
# 最简单：杀掉整个 screen，重新启动
SCREEN_PID=$(pgrep -f 'SCREEN.*qq-bot' | head -1)
if [ -n "$SCREEN_PID" ]; then
    echo "Killing old screen session"
    kill -TERM $SCREEN_PID
    sleep 3
fi

# 重新启动 screen + java
cd /opt/qq-bot
echo "Starting new screen session..."
screen -dmS qq-bot bash -c "source /opt/qq-bot/.env 2>/dev/null || true; java -Xms256m -Xmx768m -jar /opt/qq-bot/untitled-1.0-SNAPSHOT.jar >> /opt/qq-bot/qq-bot.log 2>&1"

echo ""
echo "=== Step 5: 等待 25 秒 java 启动 ==="
for i in 1 2 3 4 5; do
    sleep 5
    NEW_JAVA=$(pgrep -f 'untitled-1.0-SNAPSHOT.jar' | head -1)
    if [ -n "$NEW_JAVA" ]; then
        echo "  ${i}x5s: new java pid=$NEW_JAVA up"
        break
    fi
    echo "  ${i}x5s: waiting..."
done

# 再等 10 秒让 WebSocket 连上
echo ""
echo "=== Step 6: 等待 WebSocket 连接 ==="
for i in 1 2 3 4 5 6; do
    sleep 5
    HEARTBEAT_COUNT=$(awk '/2026-07-22 10:[2-3][0-9]/,EOF' /opt/qq-bot/qq-bot.log | grep -c heartbeat || echo 0)
    CONN=$(ss -tn | grep -c ':5701' || echo 0)
    echo "  ${i}x5s: recent heartbeats=$HEARTBEAT_COUNT connections_to_5701=$CONN"
    if [ "$HEARTBEAT_COUNT" -gt 0 ] && [ "$CONN" -gt 0 ]; then
        echo "  -> connected!"
        break
    fi
done

echo ""
echo "=== Step 7: 最终验证 ==="
echo "--- java 进程 ---"
ps -ef | grep untitled-1.0-SNAPSHOT | grep -v grep
echo ""
echo "--- NapCat 连接 ---"
ss -tn | grep 5701 || echo "still no connection"
echo ""
echo "--- 最近 15 条日志 ---"
tail -15 /opt/qq-bot/qq-bot.log
