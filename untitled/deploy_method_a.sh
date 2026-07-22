#!/bin/bash
set -e

echo "== 1. 备份当前 jar =="
cd /opt/qq-bot
ls -la untitled-1.0-SNAPSHOT.jar
cp -p untitled-1.0-SNAPSHOT.jar untitled-1.0-SNAPSHOT.jar.bak.pre_method_a
ls -la untitled-1.0-SNAPSHOT.jar.bak.* | tail -3

echo
echo "== 2. 关掉旧 bot (通过 pkill) =="
pkill -f 'untitled-1.0-SNAPSHOT.jar' || true
sleep 4
echo "  (no java 进程应该正常):"
ps -ef | grep untitled-1.0-SNAPSHOT.jar | grep -v grep || echo "  OK, 旧 bot 已关闭"

echo
echo "== 3. 验证新 jar =="
ls -la /opt/qq-bot/untitled-1.0-SNAPSHOT.jar
md5sum /opt/qq-bot/untitled-1.0-SNAPSHOT.jar

echo
echo "== 4. 启动新 bot =="
cd /opt/qq-bot
source /opt/qq-bot/.env
nohup java -Xms512m -Xmx2g -jar /opt/qq-bot/untitled-1.0-SNAPSHOT.jar > /opt/qq-bot/qq-bot.stdout.log 2>&1 &
BOT_PID=$!
echo "BOT_PID=$BOT_PID"
disown $BOT_PID 2>/dev/null || true

echo
echo "== 5. 等 8 秒启动 =="
sleep 8
ps -p $BOT_PID -o pid,etime,cmd || echo "bot died"

echo
echo "== 6. 启动日志前 20 行 =="
head -n 20 /opt/qq-bot/qq-bot.stdout.log
