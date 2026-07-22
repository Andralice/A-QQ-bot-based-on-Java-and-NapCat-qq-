#!/bin/bash
PID=$(ps -ef | grep 'untitled-1.0-SNAPSHOT.jar' | grep -v grep | grep -v SCREEN | awk '{print $2}' | head -1)
echo "PID=$PID"

echo
echo "== ErrorMonitor 线程堆栈 =="
jstack $PID 2>/dev/null | grep -A 12 'ErrorMonitor' | head -25

echo
echo "== 整个 monitorLoop 堆栈 =="
jstack $PID 2>/dev/null | grep -B 1 -A 25 'ErrorMonitor.monitorLoop' | head -40

echo
echo "== stdout.log 时间窗口 (启动到现在) 全部 WARN/ERROR =="
grep -aE 'WARN|ERROR' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -10

echo
echo "== log 文件大小 + 文件名 =="
ls -la /opt/qq-bot/qq-bot.stdout.log /opt/qq-bot/qq-bot.log 2>/dev/null
