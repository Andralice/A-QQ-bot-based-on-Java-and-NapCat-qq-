#!/bin/bash
# 重启 NapCat 脚本

echo "=== 1. 停止现有 NapCat ==="
# 优先优雅退出 alice 的 screen
sudo -u alice screen -S napcat -X quit 2>&1
sleep 3
# 兜底强杀
pkill -9 -u alice -f "/opt/QQ/qq" 2>/dev/null
pkill -9 -u alice -f "xvfb-run.*qq" 2>/dev/null
sleep 2
echo "停止完成"

echo "=== 2. 确认进程退出 ==="
REMAINING=$(ps -u alice -f | grep -E "/opt/QQ/qq" | grep -v grep | wc -l)
echo "剩余 napcat 进程数: $REMAINING"
if [ "$REMAINING" -gt 0 ]; then
    ps -u alice -f | grep -E "/opt/QQ/qq" | grep -v grep
fi

echo "=== 3. 启动 NapCat ==="
sudo -u alice screen -dmS napcat bash -c "xvfb-run -a /opt/QQ/qq --no-sandbox -q 356289140"
sleep 1
echo "启动命令已发出"

echo "=== 4. 等待启动 (15s) ==="
sleep 15

echo "=== 5. 验证状态 ==="
echo "--- 进程 ---"
ps -u alice -f | grep -E "napcat|/opt/QQ/qq" | grep -v grep
echo "--- 端口 5701 ---"
ss -tlnp 2>/dev/null | grep 5701
echo "--- 端口 5700 ---"
ss -tlnp 2>/dev/null | grep 5700
echo "--- screen 会话 ---"
sudo -u alice screen -list | grep napcat
echo "=== 完成 ==="
