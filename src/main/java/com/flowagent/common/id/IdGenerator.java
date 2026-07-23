package com.flowagent.common.id;

import com.flowagent.common.util.DateUtil;
import com.flowagent.common.util.IpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
public class IdGenerator {
    private static final long SEQUENCE_BITS = 10L;
    private static final long WORKER_ID_BITS = 7L;
    private static final long DATA_CENTER_BITS = 3L;
    private static final long SEQUENCE_MASK = (1 << SEQUENCE_BITS) - 1;
    private static final long WORKER_ID_LEFT_SHIFT_BITS = SEQUENCE_BITS;
    private static final long DATACENTER_LEFT_SHIFT_BITS = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT_BITS = WORKER_ID_LEFT_SHIFT_BITS + WORKER_ID_BITS + DATA_CENTER_BITS;

    private long workId = 1;
    private long dataCenter = 1;
    private long lastTime;
    private long sequence;
    private byte sequenceOffset;

    public IdGenerator() {
        try {
            String ip = IpUtil.getLocalIp4Address();
            String[] cells = StringUtils.split(ip, ".");
            this.dataCenter = Integer.parseInt(cells[0]) & ((1 << DATA_CENTER_BITS) - 1);
            this.workId = Integer.parseInt(cells[3]) >> 16 & ((1 << WORKER_ID_BITS) - 1);
        } catch (Exception e) {
            this.dataCenter = 1;
            this.workId = 1;
        }
    }

    public IdGenerator(int workId, int dateCenter) {
        this.workId = workId;
        this.dataCenter = dateCenter;
    }

    public synchronized Long nextId() {
        long nowTime = waitToIncrDiffIfNeed(getNowTime());
        if (lastTime == nowTime) {
            if (0L == (sequence = (sequence + 1) & SEQUENCE_MASK)) {
                nowTime = waitUntilNextTime(nowTime);
            }
        } else {
            vibrateSequenceOffset();
            sequence = sequenceOffset;
        }
        lastTime = nowTime;
        long ans = ((nowTime % DateUtil.ONE_DAY_SECONDS) << TIMESTAMP_LEFT_SHIFT_BITS)
                | (dataCenter << DATACENTER_LEFT_SHIFT_BITS)
                | (workId << WORKER_ID_LEFT_SHIFT_BITS)
                | sequence;
        if (log.isDebugEnabled()) {
            log.debug("seconds:{}, datacenter:{}, work:{}, seq:{}, ans={}",
                    nowTime % DateUtil.ONE_DAY_SECONDS, dataCenter, workId, sequence, ans);
        }
        return Long.parseLong(String.format("%s%011d", getDaySegment(nowTime), ans));
    }

    private long waitToIncrDiffIfNeed(final long nowTime) {
        if (lastTime <= nowTime) {
            return nowTime;
        }
        sleepMillis(lastTime - nowTime);
        return getNowTime();
    }

    private long waitUntilNextTime(final long lastTime) {
        long result = getNowTime();
        while (result <= lastTime) {
            result = getNowTime();
        }
        return result;
    }

    private void vibrateSequenceOffset() {
        sequenceOffset = (byte) (~sequenceOffset & 1);
    }

    private long getNowTime() {
        return System.currentTimeMillis() / 1000;
    }

    private static String getDaySegment(long time) {
        LocalDateTime localDate = DateUtil.time2LocalTime(time * 1000L);
        return String.format("%02d%03d", localDate.getYear() % 100, localDate.getDayOfYear());
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
