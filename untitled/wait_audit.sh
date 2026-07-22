#!/bin/bash
echo "== 等 7 分钟 (ErrorMonitor 启动 2min + 第一轮扫描 + 审计 API 调用) =="
sleep 420
echo
echo "== ErrorMonitor + audit API 调用结果 =="
grep -aE '异常自动监控已启动|新 ERROR|审计API|审计 API|✅ 审计|🔧 审计|判定' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -n 20
echo
echo "== stdout.log 中所有真 ERROR =="
grep -aE '^[0-9]{4}-[0-9]{2}-[0-9]{2}.*ERROR' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -n 10
