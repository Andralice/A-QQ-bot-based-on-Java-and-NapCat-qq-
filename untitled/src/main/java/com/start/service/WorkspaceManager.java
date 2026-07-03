package com.start.service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 沙箱工作区管理。创建项目副本 → 隔离修改 → 生成 diff → 确认后应用或丢弃。
 * 仅复制源码和 pom.xml，不复制 target/ 和 .git/。
 */
public class WorkspaceManager {
    private static final Logger logger = LoggerFactory.getLogger(WorkspaceManager.class);

    private final Path projectRoot;
    private Path workspaceRoot;

    public WorkspaceManager() {
        this.projectRoot = detectProjectRoot();
    }

    public Path getProjectRoot() { return projectRoot; }

    /** 创建沙箱工作区副本 */
    public synchronized Path create() throws IOException {
        if (workspaceRoot != null) {
            discard();
        }
        String wsName = "ws_" + System.currentTimeMillis();
        Path wsParent = projectRoot.getParent();
        if (wsParent == null) wsParent = projectRoot;
        workspaceRoot = wsParent.resolve(wsName);
        try {
            copySourceTo(workspaceRoot);
        } catch (IOException e) {
            try { deleteRecursive(workspaceRoot); } catch (IOException ignored) {}
            workspaceRoot = null;
            throw e;
        }
        logger.info("沙箱工作区创建: {}", workspaceRoot);
        return workspaceRoot;
    }

    /** 生成 workspace 与原始项目的统一 diff（含 src/ 和 pom.xml） */
    public synchronized String diff() throws IOException {
        if (workspaceRoot == null || !Files.exists(workspaceRoot)) {
            return "工作区不存在";
        }
        StringBuilder result = new StringBuilder();

        result.append(diffPath("src"));
        String pomDiff = diffPath("pom.xml");
        if (!pomDiff.isEmpty() && !pomDiff.equals("无变更")) {
            if (!result.isEmpty()) result.append("\n");
            result.append(pomDiff);
        }

        return result.isEmpty() ? "无变更" : result.toString();
    }

    private String diffPath(String relativePath) {
        Path src = projectRoot.resolve(relativePath);
        Path dst = workspaceRoot.resolve(relativePath);
        if (!Files.exists(src) || !Files.exists(dst)) {
            return "";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "diff", "--no-index", "--",
                    src.toString(), dst.toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "[diff 超时: " + relativePath + "]";
            }
            String output = new String(p.getInputStream().readAllBytes());
            return output.isEmpty() ? "" : output;
        } catch (Exception e) {
            return "[diff 失败: " + relativePath + " — " + e.getMessage() + "]";
        }
    }

    /** 将沙箱中的修改应用到原始项目（复制 src/ + pom.xml）。失败时从 .bak 自动恢复。 */
    public synchronized String apply() throws IOException {
        if (workspaceRoot == null || !Files.exists(workspaceRoot)) {
            return "工作区不存在";
        }

        Path srcBak = projectRoot.resolve("src.bak");
        Path pomBak = projectRoot.resolve("pom.xml.bak");
        boolean srcBackedUp = false;
        boolean pomBackedUp = false;

        try {
            // 备份 src/
            if (Files.exists(projectRoot.resolve("src"))) {
                if (Files.exists(srcBak)) deleteRecursive(srcBak);
                copyDirectory(projectRoot.resolve("src"), srcBak);
                srcBackedUp = true;
            }

            // 备份 pom.xml
            if (Files.exists(projectRoot.resolve("pom.xml"))) {
                if (Files.exists(pomBak)) Files.delete(pomBak);
                Files.copy(projectRoot.resolve("pom.xml"), pomBak, StandardCopyOption.REPLACE_EXISTING);
                pomBackedUp = true;
            }

            // 覆盖 src/
            deleteRecursive(projectRoot.resolve("src"));
            copyDirectory(workspaceRoot.resolve("src"), projectRoot.resolve("src"));

            // 覆盖 pom.xml
            Files.copy(workspaceRoot.resolve("pom.xml"), projectRoot.resolve("pom.xml"),
                    StandardCopyOption.REPLACE_EXISTING);

            // 成功后清理 .bak
            if (Files.exists(srcBak)) deleteRecursive(srcBak);
            if (Files.exists(pomBak)) Files.deleteIfExists(pomBak);

            logger.info("沙箱修改已应用到项目");
            return "已应用";
        } catch (IOException e) {
            logger.error("应用沙箱修改失败，尝试从备份恢复: {}", e.getMessage());
            // 尝试从备份恢复
            try {
                if (srcBackedUp && Files.exists(srcBak)) {
                    if (Files.exists(projectRoot.resolve("src"))) deleteRecursive(projectRoot.resolve("src"));
                    copyDirectory(srcBak, projectRoot.resolve("src"));
                }
                if (pomBackedUp && Files.exists(pomBak)) {
                    Files.copy(pomBak, projectRoot.resolve("pom.xml"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException restoreEx) {
                logger.error("从备份恢复也失败了！备份在 src.bak/ 和 pom.xml.bak，请手动恢复: {}", restoreEx.getMessage());
                return "应用失败且自动恢复也失败！请手动从 src.bak/ 和 pom.xml.bak 恢复。错误: " + e.getMessage();
            }
            throw new IOException("应用沙箱修改失败，已从备份自动恢复。原因: " + e.getMessage(), e);
        }
    }

    /** 丢弃沙箱 */
    public synchronized void discard() {
        if (workspaceRoot != null && Files.exists(workspaceRoot)) {
            try {
                deleteRecursive(workspaceRoot);
            } catch (IOException e) {
                logger.warn("清理沙箱失败 (部分文件可能残留): {}", e.getMessage());
            }
            logger.info("沙箱已丢弃: {}", workspaceRoot);
            workspaceRoot = null;
        }
    }

    // === 内部 ===

    private void copySourceTo(Path dest) throws IOException {
        Files.createDirectories(dest);
        Path srcDir = projectRoot.resolve("src");
        if (Files.exists(srcDir)) {
            copyDirectory(srcDir, dest.resolve("src"));
        }
        Path pom = projectRoot.resolve("pom.xml");
        if (Files.exists(pom)) {
            Files.copy(pom, dest.resolve("pom.xml"));
        }
    }

    private static void copyDirectory(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> failures = new ArrayList<>();
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) { failures.add(p); }
                });
        if (!failures.isEmpty()) {
            logger.warn("清理时 {} 个文件/目录无法删除: {}", failures.size(), failures);
        }
    }

    private static Path detectProjectRoot() {
        String cwd = System.getProperty("user.dir");
        Path cwdPath = Paths.get(cwd);
        if (Files.exists(cwdPath.resolve("pom.xml"))) {
            return cwdPath.toAbsolutePath().normalize();
        }
        Path optPath = Paths.get("/opt/qq-bot");
        if (Files.exists(optPath.resolve("pom.xml"))) {
            return optPath;
        }
        return cwdPath.toAbsolutePath().normalize();
    }
}
