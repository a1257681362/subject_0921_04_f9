package com.evops.schedule;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.vehicle.Vehicle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 车辆档期 Mapper。
 *
 * 并发防双占：同一车辆的占位创建在业务事务内先 {@link #lockVehicleById}
 * （SELECT ... FOR UPDATE 锁住 t_vehicle 行）串行化，使两个并行预约不会同时通过
 * 区间重叠判定而造成一车两约；库层另以档期时间轴判定为最终兜底。
 */
@Mapper
public interface VehicleScheduleMapper extends BaseMapper<VehicleSchedule> {

    /**
     * 悲观锁住被预约车辆行（须在事务内调用）。同车并发预约在此排队，
     * 后到者读到的是先到者已提交的占位，从而正确判出冲突。
     */
    @Select("SELECT * FROM t_vehicle WHERE id = #{id} FOR UPDATE")
    Vehicle lockVehicleById(@Param("id") Long id);
}
