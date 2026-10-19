package com.smartagri.gateway.idempotent;

/**
 * 幂等存储抽象：记录某 Idempotency-Key 的处理状态与已完成响应体。
 */
public interface IdempotencyStore {

    String STATUS_COMPLETED = "C";
    String STATUS_IN_PROGRESS = "P";

    /**
     * 原子地以「不存在才写入」方式标记处理中。
     *
     * @return 之前的状态（null 表示本次成功抢占；"C"/"P" 表示已被占用）
     */
    String putIfAbsent(String key, String status, long ttlSeconds);

    /** 写入已完成状态与响应体。 */
    void setCompleted(String key, String body, long ttlSeconds);

    /** 读取已完成响应体（非完成态返回 null）。 */
    String getCompletedBody(String key);

    /** 清除某 key（处理失败或过期时）。 */
    void clear(String key);
}
