package com.flowagent.common.enums;

public enum NodeStatusEnum {
    INIT,
    RUNNING,
    MARK,
    SUCCESS {
        @Override
        public boolean executed() {
            return true;
        }
    },
    ERROR {
        @Override
        public boolean executed() {
            return true;
        }
    },
    SKIP {
        @Override
        public boolean executed() {
            return true;
        }
    };

    public boolean executed() {
        return false;
    }
}
