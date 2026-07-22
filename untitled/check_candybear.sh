#!/bin/bash
echo "--- run probe4 ---"
python3 /tmp/probe4.py
echo ""
echo "=== candy bear log after 10:13 ==="
awk '/2026-07-22 10:1[3-6]/,EOF' /opt/qq-bot/qq-bot.log | grep -v heartbeat | tail -20
