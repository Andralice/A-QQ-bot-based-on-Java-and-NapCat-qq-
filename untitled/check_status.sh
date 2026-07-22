#!/bin/bash
echo "== 1. bot 进程 =="
ps -ef | grep untitled-1.0-SNAPSHOT.jar | grep -v grep | grep -v SCREEN

echo
echo "== 2. ErrorMonitor 启动 + 扫描结果 =="
grep -aE '异常自动监控已启动|新 ERROR|审计API|✅ 审计|🔧 审计|判定' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -n 20

echo
echo "== 3. stdout.log 中所有 ERROR (按新格式正则) =="
grep -aE '^[0-9]{4}-[0-9]{2}-[0-9]{2}.*ERROR' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -n 20

echo
echo "== 4. CandyBearLifeEngine 相关 (我们关心的真错误) =="
grep -aE 'CandyBearLifeEngine' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -10

echo
echo "== 5. stdout.log 当前大小 + 最后修改时间 =="
ls -la /opt/qq-bot/qq-bot.stdout.log
