#!/bin/bash
echo "== 1. 改 .env 关闭 vision =="
sed -i 's/^export VISION_ENABLED=true/export VISION_ENABLED=false/' /opt/qq-bot/.env
grep -E 'VISION_ENABLED|VISION_API_KEY|VISION_MODEL|VISION_BASE_URL' /opt/qq-bot/.env

echo
echo "== 2. 备份当前 jar (vision 卸载前的现场) =="
ls -la /opt/qq-bot/untitled-1.0-SNAPSHOT.jar
cp -p /opt/qq-bot/untitled-1.0-SNAPSHOT.jar /opt/qq-bot/untitled-1.0-SNAPSHOT.jar.bak.vision_off && echo "backup ok" || echo "backup skipped"

echo
echo "== 3. 关闭 bot =="
screen -S qq-bot -X quit
sleep 2
screen -list

echo
echo "== 4. 重新启动 bot =="
cd /opt/qq-bot
source /opt/qq-bot/.env
screen -dmS qq-bot java -Xms512m -Xmx2g -jar /opt/qq-bot/untitled-1.0-SNAPSHOT.jar
sleep 6
echo "== 5. screen 状态 =="
screen -list
echo
echo "== 6. 启动日志 =="
tail -n 30 /opt/qq-bot/qq-bot.log
