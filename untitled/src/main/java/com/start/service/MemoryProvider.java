package com.start.service;

import com.start.memory.MemoryRecall;

import java.util.List;

/** 记忆提供者接口。各 Repository 可实现此接口接入统一记忆查询。 */
public interface MemoryProvider {
    /** 提供者名称，如 "long_term" "user_profile" */
    String name();

    /** 按条件检索记忆，返回经 MemoryInterpreter 翻译后的召回结果 */
    List<MemoryRecall> search(MemoryQuery query);
}
