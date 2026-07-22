#!/bin/bash
set -e

REMOTE=/opt/qq-bot
JAR=untitled-1.0-SNAPSHOT.jar

echo "== 1. 备份当前 jar (vision_off 备份保留为更早的现场) =="
cd $REMOTE
ls -la $JAR
cp -p $JAR $JAR.bak.pre_errormonitor_fix
ls -la $JAR.bak.* | tail -5

echo
echo "== 2. 关掉旧 bot =="
screen -S qq-bot -X quit
sleep 3
screen -list || echo "(no screens)"

echo
echo "== 3. 上传新 jar =="
echo "  (scp step done by caller) "

echo
echo "== 4. 验证 jar 时间和大小 =="
ls -la $REMOTE/$JAR
md5sum $REMOTE/$JAR

echo
echo "== 5. 启动新 bot =="
cd $REMOTE
source $REMOTE/.env
nohup java -Xms512m -Xmx2g -jar $REMOTE/$JAR > $REMOTE/qq-bot.stdout.log 2>&1 &
BOT_PID=$!
echo "BOT_PID=$BOT_PID"
disown $BOT_PID 2>/dev/null || true

echo
echo "== 6. 等启动 (10s) =="
sleep 10
ps -p $BOT_PID -o pid,etime,cmd || echo "bot died"

echo
echo "== 7. 启动日志 (前 60 行，去 ANSI 颜色) =="
sed -n '1,60p' $REMOTE/qq-bot.stdout.log 2>/dev/null | head -60
