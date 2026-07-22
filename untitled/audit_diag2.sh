#!/bin/bash
# 用 Python 构造合法 JSON 测 audit API，排除 shell 转义干扰
cat > /tmp/audit_test.py <<'PYEOF'
import json
import urllib.request
import urllib.error

KEY = "sk-XqWiKSLgqh8sHofaIZf5KV2qZ0Om9aGJA8ycJS8HaJ2CmznQ"
URL = "https://api.mytokenland.com/v1/chat/completions"

# 模拟 ErrorMonitor 实际发出的 prompt
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
        "max_tokens": 300,
        "temperature": 0.1
    }
    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(URL, data=payload, method="POST",
        headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})

    print(f"\n========== model={model} ==========")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read().decode("utf-8")
            print(f"HTTP {resp.status}")
            j = json.loads(data)
            content = j.get("choices", [{}])[0].get("message", {}).get("content", "")
            print(f"content.length={len(content)}")
            print(f"content.preview={content[:300]}")
            if not content:
                # 看看完整结构
                print(f"full response (前 500 字符): {data[:500]}")
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} {e.reason}")
        print(f"body: {e.read().decode('utf-8', errors='replace')[:500]}")
    except Exception as e:
        print(f"EXCEPTION: {e}")
PYEOF

python3 /tmp/audit_test.py
