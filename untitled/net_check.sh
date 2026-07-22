#!/bin/bash
echo "=================================="
echo " 1. 本机所有 IP (ip addr)"
echo "=================================="
ip -4 addr show 2>/dev/null
echo
echo "--- 重点：有没有 172.21.0.4 这个 IP 在本机？ ---"
ip -4 addr show 2>/dev/null | grep -E '172\.21|172\.16'
[ $? -ne 0 ] && echo "  ✅ 本机没有 172.21.0.x 段 IP"
echo
echo "=================================="
echo " 2. 路由表 (172.21.0.4 走哪个网卡)"
echo "=================================="
ip route get 172.21.0.4 2>&1
echo
echo "--- 默认路由 ---"
ip route show default
echo
echo "=================================="
echo " 3. ARP 表 (172.21.0.4 的 MAC)"
echo "=================================="
ip neigh show 172.21.0.4 2>&1
echo
echo "--- 所有 172.21.x 邻居 ---"
ip neigh show 2>/dev/null | grep -E '172\.21' | head -10
echo
echo "=================================="
echo " 4. 本机 hostname / 区域"
echo "=================================="
hostname
hostname -I 2>/dev/null
echo
echo "--- 腾讯云 metadata ---"
curl -s --max-time 3 http://169.254.169.254/latest/meta-data/instance/instance-id 2>&1 | head -1
curl -s --max-time 3 http://169.254.169.254/latest/meta-data/instance-id 2>&1 | head -1
echo
echo "=================================="
echo " 5. 反向 SSH 到 172.21.0.1 的进程链 (腾讯云监控)"
echo "=================================="
echo "--- 进程 1624477 的父进程 + 启动时间 ---"
ps -o pid,ppid,etime,cmd -p 1624477 2>/dev/null
echo
echo "--- 启动这个 ssh 的 sshd 会话 ---"
ps -o pid,ppid,etime,cmd -p 1624478 2>/dev/null
echo
echo "--- 看是谁拉起了 1624478 sshd (1624430) ---"
ps -o pid,ppid,etime,cmd -p 1624430 2>/dev/null
echo
echo "--- 1624430 的父进程是谁 ---"
PPID_OF_4430=$(ps -o ppid= -p 1624430 2>/dev/null | tr -d ' ')
ps -o pid,ppid,etime,cmd -p $PPID_OF_4430 2>/dev/null
echo
echo "--- 1624430 是 root 登录会话，PPID=1 是 systemd ---"
echo
echo "--- 关键问题: 1624430 (root sshd) 是谁用 key 登录的 ---"
echo "  -- 父进程: 应该是 211.99.216.18 (你) --"
ss -tnp 2>/dev/null | grep -E '1624430|1624478|1624479' | head -5
echo
echo "=================================="
echo " 6. 172.21.0.4 那个公钥 (SHA256:1/QcoKqA6AiOh2L7ctEpBo7If1eX/lZwmnf28DPlIcs) 在哪？"
echo "=================================="
echo "--- 搜全盘哪个 authorized_keys 含这个公钥的 fingerprint ---"
grep -r "1/QcoKqA6AiOh2L7ctEpBo7If1eX/lZwmnf28DPlIcs" /root/.ssh/ /home/*/.ssh/ /etc/ssh/ 2>/dev/null
echo
echo "--- 直接搜 known_hosts 之类 ---"
grep -r "172.21.0.4" /root/.ssh/ 2>/dev/null
echo
echo "=================================="
echo " 7. 关键可疑进程深挖：1621244/1621313/1621372 (11:22-11:23 公钥登录留下的 sshd)"
echo "=================================="
for pid in 1621244 1621313 1621372 1622333 1623382; do
    echo "--- PID $pid ---"
    ps -o pid,ppid,etime,stat,cmd -p $pid 2>/dev/null
done
echo
echo "=================================="
echo " 8. ls -la /proc/<上面那些PID>/cwd 看它们在哪个目录跑"
echo "=================================="
for pid in 1621244 1621313 1621372; do
    if [ -d /proc/$pid ]; then
        echo "--- PID $pid cwd: $(ls -la /proc/$pid/cwd 2>&1 | tail -1) ---"
        echo "--- PID $pid exe: $(ls -la /proc/$pid/exe 2>&1 | tail -1) ---"
        echo "--- PID $pid fd 列表 ---"
        ls -la /proc/$pid/fd 2>/dev/null | head -10
        echo
    fi
done
