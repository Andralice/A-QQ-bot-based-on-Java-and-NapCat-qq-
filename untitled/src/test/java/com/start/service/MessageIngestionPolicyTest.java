package com.start.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MessageIngestionPolicyTest {

    @Test
    void sourceEventKeyScopesGroupMessagesAndKeepsRedeliveryStable() {
        String first = MessageService.sourceEventKey("group", "10001", "20002", "30003");
        String replay = MessageService.sourceEventKey("group", "10001", "20002", "30003");
        String anotherGroup = MessageService.sourceEventKey("group", "10002", "20002", "30003");

        assertEquals("group:10001:20002:30003", first);
        assertEquals(first, replay);
        assertEquals("group:10002:20002:30003", anotherGroup);
    }

    @Test
    void sourceEventKeyDoesNotInventIdentityForLegacyEvents() {
        assertNull(MessageService.sourceEventKey("private", null, "20002", ""));
    }
}
