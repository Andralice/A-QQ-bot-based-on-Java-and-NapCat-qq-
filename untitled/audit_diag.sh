#!/bin/bash
echo "== 1. BotConfig 当前 audit 配置 =="
grep -aE 'audit\.|AUDIT' /opt/qq-bot/.env

echo
echo "== 2. ErrorMonitor 实际发出的 audit 请求 prompt (从 stdout.log 抓最近一次) =="
grep -aA 12 '审计 API' /opt/qq-bot/qq-bot.stdout.log 2>/dev/null | tail -25

echo
echo "== 3. 列出 mytokenland 支持的模型 =="
curl -sS 'https://api.mytokenland.com/v1/models' -H 'Authorization: Bearer sk-XqWiKSLgqh8sHofaIZf5KV2qZ0Om9aGJA8ycJS8HaJ2CmznQ' -w '\n--HTTP %{http_code}--\n' --max-time 15 | head -c 3500

echo
echo "== 4. 用 audit key 打 claude-sonnet-4-6 模型 (BotConfig 配的) =="
PROMPT='你是一个日志分析器。以下是服务器日志中发现的 2 条异常。

```
2026-07-16 12:08:14.495 [main] ERROR c.start.service.CandyBearLifeEngine - [LifeEngine] 日记生成失败 2026-07-14: Duplicate entry '\''2026-07-14'\'' for key '\''candy_bear_daily_journals.journal_date'\''
2026-07-16 12:08:34.407 [main] ERROR c.start.service.CandyBearLifeEngine - [LifeEngine] 周记生成失败: Duplicate entry '\''2026-07-13'\'' for key '\''candy_bear_weekly_diaries.uk_week_start'\''
```

请用2-4句话回复：
1. 这些错误的类型和严重程度（严重/一般/可忽略）
2. 是否有需要立即修复的问题
回复格式：'\''[严重程度] 结论。需要修复：是/否。原因：...'\'''

PROMPT_B64=$(echo -n "$PROMPT" | base64 -w 0)
cat > /tmp/audit_req.json <<EOF
{"model":"claude-sonnet-4-6","messages":[{"role":"user","content":"$PROMPT"}],"max_tokens":300,"temperature":0.1}
EOF
echo "  request body (前 200 字符):"
head -c 200 /tmp/audit_req.json
echo
echo "  response:"
curl -sS -X POST 'https://api.mytokenland.com/v1/chat/completions' \
  -H 'Authorization: Bearer sk-XqWiKSLgqh8sHofaIZf5KV2qZ0Om9aGJA8ycJS8HaJ2CmznQ' \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/audit_req.json \
  -w '\n--HTTP %{http_code}--\n' --max-time 30

echo
echo "== 5. 同 prompt + model=MiniMax-M2.7 (mytokenland 默认模型) =="
cat > /tmp/audit_req2.json <<EOF
{"model":"MiniMax-M2.7","messages":[{"role":"user","content":"$PROMPT"}],"max_tokens":300,"temperature":0.1}
EOF
curl -sS -X POST 'https://api.mytokenland.com/v1/chat/completions' \
  -H 'Authorization: Bearer sk-XqWiKSLgqh8sHofaIZf5KV2qZ0Om9aGJA8ycJS8HaJ2CmznQ' \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/audit_req2.json \
  -w '\n--HTTP %{http_code}--\n' --max-time 30
