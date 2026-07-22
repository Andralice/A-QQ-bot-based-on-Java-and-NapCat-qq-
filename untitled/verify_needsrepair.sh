#!/bin/bash
echo "== 1. bot 状态 =="
ps -ef | grep untitled-1.0-SNAPSHOT.jar | grep -v grep | grep -v SCREEN
echo
echo "== 2. ErrorMonitor 启动情况 =="
grep -aE '异常自动监控已启动' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -3
echo
echo "== 3. 最近扫描结果 (含 needsRepair 判定) =="
grep -aE '新 ERROR|审计API: 结论|✅ 审计|🔧 审计|审计 API 判定|判定' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -10
echo
echo "== 4. 是否触发主 AI 修复 =="
grep -aE '触发主 AI|triggerMainAiFix' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -3
echo
echo "== 5. 真 ERROR (新格式) =="
grep -aE '^[0-9]{4}-[0-9]{2}-[0-9]{2}.*ERROR' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -8
