#!/bin/bash
# 抓出所有 ErrorMonitor + audit 相关日志，存到文件方便传回
grep -aE '异常自动监控已启动|新 ERROR|审计API: 结论|✅ 审计|🔧 审计|审计 API 判定|触发主 AI|findLogFile|扫描|ErrorMonitor-' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null > /tmp/monitor_log.txt
wc -l /tmp/monitor_log.txt
echo
echo "== 完整内容 =="
cat /tmp/monitor_log.txt
