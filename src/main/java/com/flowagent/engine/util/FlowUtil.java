package com.flowagent.engine.util;

import java.util.UUID;

/**
 */
public class FlowUtil {

    public static String genWorkflowId(String flow) {
        return "workflow#" + flow + "-" + System.currentTimeMillis();
    }


    public static String genSid() {
        return UUID.randomUUID().toString();
    }

    public static String genInterruptEventId() {
        return "interrupt";
    }
}
