package com.start.service;

import com.start.memory.MemoryInterpreter;
import com.start.memory.MemoryRecall;
import com.start.config.DatabaseConfig;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * LongTermMemoryRepository 的 MemoryProvider 适配器。
 * 使用 MemoryInterpreter 将 DB 记录翻译为记忆召回结果。
 */
public class LongTermMemoryProvider implements MemoryProvider {

    private final LongTermMemoryRepository repo;
    private final MemoryInterpreter interpreter;

    public LongTermMemoryProvider() {
        this.repo = new LongTermMemoryRepository(DatabaseConfig.getDataSource());
        this.interpreter = new MemoryInterpreter();
    }

    public LongTermMemoryProvider(LongTermMemoryRepository repo, MemoryInterpreter interpreter) {
        this.repo = repo;
        this.interpreter = interpreter;
    }

    @Override
    public String name() { return "long_term"; }

    @Override
    public List<MemoryRecall> search(MemoryQuery query) {
        try {
            List<LongTermMemory> results = repo.search(query.userId(), query.groupId(),
                    query.keyword(), query.limit());
            if (results.isEmpty()) return Collections.emptyList();

            // 每次召回均更新计数和时间，不再区分自动/主动路径
            for (LongTermMemory m : results) {
                try {
                    repo.markRecalled(m.getId());
                    repo.markUsed(m.getId());
                } catch (Exception ignored) {}
            }

            // 取最近记忆扩展矛盾检测上下文，避免仅限当前关键字批次
            List<LongTermMemory> conflictContext = null;
            if (results.size() >= 2) {
                try {
                    conflictContext = repo.findRecentByUser(query.userId(), query.groupId(), 30);
                } catch (Exception ignored) {}
            }

            return interpreter.interpretAll(results, conflictContext);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
