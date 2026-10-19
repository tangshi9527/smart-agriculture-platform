package com.smartagri.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AlertRecordRepository extends JpaRepository<AlertRecord, Long> {

    List<AlertRecord> findByStatusOrderByTsDesc(String status);

    List<AlertRecord> findAllByOrderByTsDesc();

    /**
     * 条件恢复时把该设备该规则下所有仍在 ACTIVE 的记录置为 RESOLVED。
     *
     * <p>为什么不用单条 id：{@code AlertEvent.id} 是运行时 UUID，没有落进 {@code AlertRecord}，
     * 两者无法直接关联；而 (deviceId, ruleId) 是稳定的业务键，同一规则重复触发时可能有
     * 多条历史 ACTIVE（去重只挡内存中的重复，不挡重启后的重复），这里一次性全部消解。</p>
     *
     * @return 受影响行数，0 表示本来就没人处于 ACTIVE
     */
    @Modifying
    @Transactional
    @Query("update AlertRecord r set r.status = 'RESOLVED' "
            + "where r.deviceId = :deviceId and r.ruleId = :ruleId and r.status = 'ACTIVE'")
    int resolveActive(@Param("deviceId") String deviceId, @Param("ruleId") String ruleId);
}
