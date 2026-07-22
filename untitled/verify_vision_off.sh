#!/bin/bash
PID=$(ps -ef | grep 'untitled-1.0-SNAPSHOT.jar' | grep -v grep | grep -v SCREEN | awk '{print $2}' | head -1)
echo "java PID=$PID"

echo
echo "== jcmd 看 system properties (vision.enabled 不会进这里，但确认 java 启动时设了哪些) =="
jcmd $PID VM.system_properties 2>/dev/null | grep -iE 'vision|user.dir' | head -5

echo
echo "== 退而求其次：直接看 .env 确实改了 =="
grep -E '^export VISION_' /opt/qq-bot/.env

echo
echo "== BotConfig.java 静态块读的是 vision.enabled 这个 key =="
echo "  application.properties 里: vision.enabled=\${VISION_ENABLED:true}"
echo "  EnvResolver.resolve(\"false\") -> \"false\""
echo "  Boolean.parseBoolean(\"false\") -> false"

echo
echo "== BaiLianService 第 1245 行保护 =="
sed -n '1240,1260p' /opt/qq-bot/untitled/src/main/java/com/start/service/BaiLianService.java 2>/dev/null
