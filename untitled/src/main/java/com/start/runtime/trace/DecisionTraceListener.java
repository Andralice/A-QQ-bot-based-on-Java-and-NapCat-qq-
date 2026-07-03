package com.start.runtime.trace;

import com.start.model.DecisionTrace;
import com.start.runtime.RuntimeEvent;
import com.start.runtime.RuntimeListener;
import com.start.service.GenerationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将 CommitFinished 事件转为 DecisionTrace 日志 + WebDashboard 记录。 */
public class DecisionTraceListener implements RuntimeListener {
    private static final Logger logger = LoggerFactory.getLogger("com.start.decision");

    @Override
    public void onEvent(RuntimeEvent e) {
        if (e instanceof RuntimeEvent.CommitFinished f) {
            GenerationResult r = f.result();
            if (r == null) return;

            String dec = r.isSilent() ? "SILENT" : r.isError() ? "ERROR" : "REPLY";
            String reason = r.isSilent() ? "model_no_reply" : "ok";
            int tools = r.toolCalls();
            int tokens = r.tokensUsed();

            DecisionTrace trace = new DecisionTrace(System.currentTimeMillis(), f.groupId(), f.userId(),
                    "GENERATED", dec, reason, tools, tokens, f.latencyMs(), 0, 0, false);
            logger.info(trace.toLogLine());

            WebDashboardListener.recordDecision(f.groupId(), f.userId(), "GENERATED", dec, reason,
                    tools, tokens, f.latencyMs());
        }
    }
}
