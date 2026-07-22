#!/bin/bash
echo "=================================="
echo " 1. 所有 docker 容器"
echo "=================================="
docker ps -a --format "table {{.ID}}\t{{.Image}}\t{{.Names}}\t{{.Status}}\t{{.Ports}}" 2>&1
echo
echo "=================================="
echo " 2. 哪个容器在 172.21.0.4 (用 docker network inspect)"
echo "=================================="
docker network inspect br-fbdf18cf9be4 2>/dev/null | head -80
echo
echo "=================================="
echo " 3. 172.21.0.4 容器的镜像信息 (通过 IP 查)"
echo "=================================="
# docker inspect <bridge> 拿不到容器，但可以列所有 bridge 关联的容器
for net in $(docker network ls -q 2>/dev/null); do
    name=$(docker network inspect $net 2>/dev/null | grep -oP '"Name":\s*"\K[^"]+' | head -1)
    echo "--- Network: $name ---"
    docker network inspect $net 2>/dev/null | grep -B 2 -A 10 'IPv4Address.*172\.21' | head -30
done
echo
echo "=================================="
echo " 4. 全部 docker 网络"
echo "=================================="
docker network ls 2>&1
echo
echo "=================================="
echo " 5. 镜像列表 (可疑的？)"
echo "=================================="
docker images --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.CreatedSince}}\t{{.ID}}" 2>&1
echo
echo "=================================="
echo " 6. 看 172.21.0.4 容器是否在跑 (通过进程和端口)"
echo "=================================="
# 用 nsenter 找对应 PID 或者 ss 看 172.21.0.4:port 上的服务
ss -tnp 2>/dev/null | grep '172.21.0.4' | head -10
echo
echo "--- 哪个进程在监听 172.21.0.4 上的端口？---"
ss -tlnp 2>/dev/null | grep '172.21' | head -10
echo
echo "=================================="
echo " 7. 容器内进程是否在 sshd (最关键的判断)"
echo "=================================="
# 用 nsenter 进容器不现实，但可以通过 /proc 看容器里的进程
# 或者直接 ps 看 host 上有没有容器内的进程（默认 unshare cgroup 看不到）
# 我们用 docker top 看
for c in $(docker ps -q 2>/dev/null); do
    name=$(docker inspect --format '{{.Name}}' $c 2>/dev/null)
    echo "--- 容器 $name ($c) 的进程 ---"
    docker top $c 2>/dev/null | head -10
done
echo
echo "=================================="
echo " 8. 容器日志: 哪个在 22 端口 (sshd)?"
echo "=================================="
for c in $(docker ps -q 2>/dev/null); do
    ports=$(docker inspect --format '{{.NetworkSettings.Ports}}' $c 2>/dev/null)
    if echo "$ports" | grep -q '22'; then
        echo "--- 容器 $c 暴露 22 端口: $ports ---"
        docker logs --tail 20 $c 2>&1 | head -30
    fi
done
