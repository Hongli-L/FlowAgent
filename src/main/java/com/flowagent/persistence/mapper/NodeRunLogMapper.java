package com.flowagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowagent.persistence.entity.NodeRunLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Node run log mapper.
 */
@Mapper
public interface NodeRunLogMapper extends BaseMapper<NodeRunLogEntity> {

    @Select("SELECT * FROM node_run_log WHERE execution_id = #{executionId} ORDER BY start_time ASC")
    List<NodeRunLogEntity> selectByExecutionId(@Param("executionId") Long executionId);
}
