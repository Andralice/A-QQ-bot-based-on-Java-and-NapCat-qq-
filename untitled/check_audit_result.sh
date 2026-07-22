#!/bin/bash
echo "== 1. bot 进程 =="
ps -ef | grep untitled-1.0-SNAPSHOT.jar | grep -v grep | grep -v SCREEN

echo
echo "== 2. ErrorMonitor 所有扫描结果 (新格式) =="
grep -aE '异常自动监控已启动|新 ERROR|审计API|审计 API|✅ 审计|🔧 审计|判定|审计 API 连续|审计 API 余额' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -n 25

echo
echo "== 3. 完整结论文本 (前 600 字符) =="
grep -aE '审计API: 结论' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -n 5

echo
echo "== 4. 真实 ERROR 列表 =="
grep -aE '^[0-9]{4}-[0-9]{2}-[0-9]{2}.*ERROR' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -n 8

echo
echo "== 5. 是否触发主 AI 修复 =="
grep -aE '触发主 AI|triggerMainAiFix|主 AI 修复' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -n 5
