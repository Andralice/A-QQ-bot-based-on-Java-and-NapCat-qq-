#!/bin/bash
cat > /tmp/test_big.py <<'PYEOF'
import json, urllib.request
KEY = "sk-XqWiKSLgqh8sHofaIZf5KV2qZ0Om9aGJA8ycJS8HaJ2CmznQ"
URL = "https://api.mytokenland.com/v1/chat/completions"
PROMPT = """你是一个日志分析器。以下是服务器日志中发现的 2 条异常。

```
2026-07-16 12:08:14.495 [main] ERROR c.start.service.CandyBearLifeEngine - [LifeEngine] 日记生成失败 2026-07-14: Duplicate entry '2026-07-14' for key 'candy_bear_daily_journals.journal_date'
2026-07-16 12:08:34.407 [main] ERROR c.start.service.CandyBearLifeEngine - [LifeEngine] 周记生成失败: Duplicate entry '2026-07-13' for key 'candy_bear_weekly_diaries.uk_week_start'
```

请用2-4句话回复：
1. 这些错误的类型和严重程度（严重/一般/可忽略）
2. 是否有需要立即修复的问题
回复格式：'[严重程度] 结论。需要修复：是/否。原因：...'"""

for model in ["claude-sonnet-4-6", "MiniMax-M2.7", "deepseek-v4-pro", "deepseek-v4-flash"]:
    body = {
        "model": model,
        "messages": [{"role": "user", "content": PROMPT}],
        "max_tokens": 2000,
        "temperature": 0.1
    }
    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(URL, data=payload, method="POST",
        headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json",
                 "User-Agent": "Java-HttpClient/17.0.19"})

    print(f"\n========== model={model} (max_tokens=2000) ==========")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = resp.read().decode("utf-8")
            j = json.loads(data)
            choices = j.get("choices", [])
            if not choices:
                print("NO choices")
                continue
            msg = choices[0].get("message", {})
            content = msg.get("content", "")
            reasoning = msg.get("reasoning_content", "")
            print(f"finish_reason={choices[0].get('finish_reason')}")
            print(f"content.length={len(content)}  reasoning.length={len(reasoning)}")
            print("--- content (前 400) ---")
            print(content[:400] if content else "(空)")
            print("--- reasoning (前 200) ---")
            print(reasoning[:200] if reasoning else "(空)")
    except Exception as e:
        print(f"EXCEPTION: {e}")
PYEOF
python3 /tmp/test_big.py
