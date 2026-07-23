package com.flowagent.persistence.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.flowagent.persistence.mapper")
public class MybatisConfig {
}
