# SSH 密钥信息记录（糖果熊 CandyBear-QQ-BOT 部署用）

记录时间: 2025-08-10 (UTC)

## 密钥文件
- 私钥路径: `/root/.ssh/id_ed25519`
- 公钥路径: `/root/.ssh/id_ed25519.pub`
- 已知主机: `/root/.ssh/known_hosts`（已包含 github.com ED25519 host key）

## 公钥内容
```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJxkvyTiG8PuRyYn1qGQUg9KhH/i5wHP4d+jbU7s5yo9 andri@devcontainer
```

## 指纹
- SHA256: `SHA256:k+OF9j09eznvPTqJNhgrm5X7SbzcPCTJHxc+vHVjJf8`
- 类型: ED25519

## SSH 认证验证
命令: `ssh -T git@github.com`
结果: `Hi Andralice/CandyBear-QQ-BOT! You've successfully authenticated, but GitHub does not provide shell access.`
→ GitHub SSH 认证可通，可推送/拉取 `Andralice/CandyBear-QQ-BOT` 仓库。

## Git remote（糖果熊仓库，已切换 SSH）
```
origin	git@github.com:Andralice/CandyBear-QQ-BOT.git (fetch)
origin	git@github.com:Andralice/CandyBear-QQ-BOT.git (push)
```

## 推送记录
- 提交: 1127066（andeli-82.md，安德里82更新-8.7）
- 远程 main 当前 HEAD: ab9d5a1（merge commit，包含 1127066 与远程 15b0f63/6c940f4）
- 远程 main 已包含 commit 1127066 ✓
