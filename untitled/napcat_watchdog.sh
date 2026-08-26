#!/bin/bash
# NapCat 看门狗 - 监控 NapCat → Java 消息转发链路，死了/卡了自动重启
#
# 部署：
#   1. 把这个脚本放到 /opt/qq-bot/scripts/napcat_watchdog.sh
#   2. chmod +x /opt/qq-bot/scripts/napcat_watchdog.sh
#   3. crontab -e 加一行：*/1 * * * * /opt/qq-bot/scripts/napcat_watchdog.sh >> /var/log/napcat_watchdog.log 2>&1
#
# 判定逻辑（任一触发即重启）：
#   1. NapCat 进程不存在
#   2. 5701 端口未监听
#   3. Java 日志最近 3 分钟没收到 user_id 非 0 的事件（message 事件），
#      且 nt_msg.db 在最近 3 分钟有更新（QQ 在收消息）
#      → NapCat 收到了消息但没转发 = 卡死

LOG_PREFIX="[$(date '+%Y-%m-%d %H:%M:%S')]"
ALERT_LOG="/var/log/napcat_watchdog.log"
QQ="356289140"
NOW=$(date +%s)

restart_napcat() {
    local reason="$1"
    echo "$LOG_PREFIX [ALERT] $reason - 强制重启 NapCat" >> $ALERT_LOG
    # 1. 杀掉现有 NapCat
    pkill -9 -u alice -f "/opt/QQ/qq --no-sandbox" 2>/dev/null
    pkill -9 -f "xvfb-run.*qq.*--no-sandbox" 2>/dev/null
    sudo -u alice screen -S napcat -X quit 2>/dev/null
    sleep 5
    # 2. 重新启动
    sudo -u alice screen -dmS napcat bash -c "xvfb-run -a /opt/QQ/qq --no-sandbox -q $QQ"
    sleep 1
    # 3. 重置状态
    echo 0 > /tmp/napcat_watchdog_stuck_count
    echo "$LOG_PREFIX [RECOVER] NapCat 已重启，新 PID=$(pgrep -f "/opt/QQ/qq --no-sandbox" | head -1)" >> $ALERT_LOG
}

# === 1. 进程存活检查 ===
QQ_PID=$(pgrep -f "/opt/QQ/qq" | head -1)
if [ -z "$QQ_PID" ]; then
    restart_napcat "NapCat QQ 进程不存在"
    exit 1
fi

# === 2. 5701 端口监听检查 ===
if ! ss -tlnp 2>/dev/null | grep -q ":5701 "; then
    restart_napcat "5701 端口未监听"
    exit 1
fi

# === 3. Java 端消息转发检查 ===
# 最近 3 分钟 Java 日志里有没有 user_id 非 0 的事件（即 message 事件）
JAVA_LOG="/opt/qq-bot/qq-bot.log"
JAVA_MSG_AGE_FILE="/tmp/napcat_watchdog_last_msg_age"
LAST_MSG_TS_FILE="/tmp/napcat_watchdog_last_msg_ts"

# 用日志文件 mtime 作为"最后写入时间"近似值（QQ 群消息频繁时 mtime 持续更新）
# 更准确的方式是 grep last user_id=... 但 grep 大文件慢，先用 mtime
if [ -f "$JAVA_LOG" ]; then
    LAST_WRITE=$(stat -c %Y "$JAVA_LOG" 2>/dev/null)
    JAVA_WRITE_AGE=$((NOW - LAST_WRITE))
else
    JAVA_WRITE_AGE=999999
fi

# QQ 是否在线（nt_msg.db 在最近 3 分钟有更新）
NT_MSG_DB=$(ls -t /home/alice/.config/QQ/nt_qq_*/nt_db/nt_msg.db 2>/dev/null | head -1)
if [ -n "$NT_MSG_DB" ]; then
    DB_MTIME=$(stat -c %Y "$NT_MSG_DB" 2>/dev/null)
    DB_AGE=$((NOW - DB_MTIME))
else
    DB_AGE=999999
fi

STUCK_COUNT_FILE="/tmp/napcat_watchdog_stuck_count"
STUCK_COUNT=$(cat $STUCK_COUNT_FILE 2>/dev/null || echo 0)

# 条件：Java 日志文件 3 分钟内没写入（即没有新事件，包括心跳）
# 但 nt_msg.db 3 分钟内有更新（QQ 在收消息）
# 连续 3 次（3 分钟）即触发重启
if [ "$JAVA_WRITE_AGE" -gt 180 ] && [ "$DB_AGE" -lt 180 ]; then
    STUCK_COUNT=$((STUCK_COUNT + 1))
    echo $STUCK_COUNT > $STUCK_COUNT_FILE
    if [ "$STUCK_COUNT" -ge 3 ]; then
        restart_napcat "NapCat 卡死（连续 3 次检测：Java 3 分钟无新事件但 QQ 在收消息）"
        exit 1
    fi
else
    # 状态正常，重置计数
    echo 0 > $STUCK_COUNT_FILE
fi

# === 4. 状态汇总日志（每 5 分钟输出一次，避免日志爆炸）===
LAST_SUMMARY_FILE="/tmp/napcat_watchdog_last_summary"
LAST_SUMMARY=$(cat $LAST_SUMMARY_FILE 2>/dev/null || echo 0)
if [ $((NOW - LAST_SUMMARY)) -ge 300 ]; then
    echo "$LOG_PREFIX [OK] NapCat 健康 PID=$QQ_PID JAVA_WRITE_AGE=${JAVA_WRITE_AGE}s DB_AGE=${DB_AGE}s STUCK=${STUCK_COUNT}" >> $ALERT_LOG
    echo $NOW > $LAST_SUMMARY_FILE
fi

exit 0
