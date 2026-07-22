#!/bin/bash
# 服务器入侵排查 — 全方位抓可疑痕迹
echo "=================================="
echo " 1. 当前在线用户 / SSH 会话"
echo "=================================="
who
w
echo
echo "=================================="
echo " 2. 最近登录记录 (last 30)"
echo "=================================="
last -n 30 2>/dev/null | head -40
echo
echo "=================================="
echo " 3. 失败登录 (lastb 30)"
echo "=================================="
lastb -n 30 2>/dev/null | head -40
echo
echo "=================================="
echo " 4. 当前 active 网络连接 (按进程)"
echo "=================================="
ss -tnp 2>/dev/null | head -30
echo
echo "=================================="
echo " 5. 监听中的端口"
echo "=================================="
ss -tlnp 2>/dev/null
echo
echo "=================================="
echo " 6. /etc/passwd 中可疑用户 (uid=0 / 无密码 / 异常 shell)"
echo "=================================="
awk -F: '($3 == 0) {print}' /etc/passwd
echo "--- 所有能登录的用户 (shell 是 sh/bash/zsh 的) ---"
awk -F: '($7 ~ /(sh|bash|zsh)$/) {print $1, $3, $7}' /etc/passwd
echo
echo "=================================="
echo " 7. SSH 授权公钥 (root + 所有用户)"
echo "=================================="
for h in /root /home/*; do
    if [ -f "$h/.ssh/authorized_keys" ]; then
        echo "--- $h/.ssh/authorized_keys ---"
        cat "$h/.ssh/authorized_keys" 2>/dev/null
    fi
done
echo
echo "=================================="
echo " 8. 计划任务 (root + 系统级)"
echo "=================================="
echo "--- root crontab ---"
crontab -l 2>/dev/null || echo "(无)"
echo "--- /etc/crontab ---"
cat /etc/crontab 2>/dev/null | grep -v '^#' | grep -v '^$'
echo "--- /etc/cron.d/ ---"
ls -la /etc/cron.d/ 2>/dev/null
echo "--- /var/spool/cron/ ---"
ls -la /var/spool/cron/ 2>/dev/null
echo
echo "=================================="
echo " 9. 最近 24h 新建/修改的系统关键文件"
echo "=================================="
find /etc /usr/bin /usr/sbin /bin /sbin -mtime -1 -type f 2>/dev/null | head -30
echo
echo "=================================="
echo " 10. /tmp /var/tmp /dev/shm 下可疑文件"
echo "=================================="
ls -la /tmp /var/tmp /dev/shm 2>/dev/null | grep -v "^total" | head -40
echo
echo "=================================="
echo " 11. 进程 CPU/内存占用 TOP 20"
echo "=================================="
ps auxf 2>/dev/null | head -2
ps auxf 2>/dev/null | tail -n +3 | sort -k3 -nr | head -20
echo
echo "=================================="
echo " 12. 异常网络外连（按状态）"
echo "=================================="
ss -tnp state established 2>/dev/null | head -20
echo
echo "=================================="
echo " 13. SSH 当前 auth.log 失败记录 (最近 50)"
echo "=================================="
grep -aE 'Failed password|Invalid user|Accepted' /var/log/auth.log 2>/dev/null | tail -50
echo
echo "=================================="
echo " 14. 安全工具是否安装"
echo "=================================="
for t in rkhunter chkrootkit clamav fail2ban; do
    which $t 2>/dev/null && echo "$t 已装" || echo "$t 未装"
done
