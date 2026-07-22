#!/bin/bash
set -e
echo "== 1. 备份 =="
cd /opt/qq-bot
cp -p untitled-1.0-SNAPSHOT.jar untitled-1.0-SNAPSHOT.jar.bak.pre_profession_random
ls -la untitled-1.0-SNAPSHOT.jar.bak.* | tail -5

echo
echo "== 2. 关 bot =="
pkill -f 'untitled-1.0-SNAPSHOT.jar' || true
sleep 4
ps -ef | grep untitled-1.0-SNAPSHOT.jar | grep -v grep || echo "  OK 已关"

echo
echo "== 3. 验证新 jar =="
ls -la /opt/qq-bot/untitled-1.0-SNAPSHOT.jar
md5sum /opt/qq-bot/untitled-1.0-SNAPSHOT.jar

echo
echo "== 4. 启动 =="
source /opt/qq-bot/.env
nohup java -Xms512m -Xmx2g -jar /opt/qq-bot/untitled-1.0-SNAPSHOT.jar > /opt/qq-bot/qq-bot.stdout.log 2>&1 &
PID=$!
echo "PID=$PID"
disown $PID 2>/dev/null || true
sleep 8
ps -p $PID -o pid,etime,cmd || echo "died"
echo
echo "== 5. 启动日志前 15 行 =="
head -n 15 /opt/qq-bot/qq-bot.stdout.log
