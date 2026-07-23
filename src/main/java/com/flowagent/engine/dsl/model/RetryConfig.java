package com.flowagent.engine.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetryConfig {

    @JsonProperty("shouldRetry")
    private Boolean shouldRetry;

    @JsonProperty("maxRetries")
    private Integer maxRetries;

    @JsonProperty("errorStrategy")
    private Integer errorStrategy;

    @JsonProperty("timeout")
    private Float timeout;

    @JsonProperty("customOutput")
    private Map<String, Object> customOutput;

    @JsonProperty("retryStrategy")
    private Integer retryStrategy;

    @JsonProperty("retryInterval")
    private Float retryInterval;

    public boolean timeOutEnabled() {
        return timeout != null && timeout > 0.001;
    }

    public long toMillis() {
        return (long) (timeout * 1000);
    }
}
