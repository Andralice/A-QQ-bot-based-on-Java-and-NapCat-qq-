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
            return interpreter.interpretAll(results);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
