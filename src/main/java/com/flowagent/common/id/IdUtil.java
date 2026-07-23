package com.flowagent.common.id;

public class IdUtil {
    public static final IdGenerator DEFAULT_ID_PRODUCER = new IdGenerator();

    public static Long genId() {
        return DEFAULT_ID_PRODUCER.nextId();
    }
}
