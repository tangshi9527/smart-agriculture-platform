package com.smartagri.command;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备控制指令记录（云端下发 → 设备执行 → 回执闭环）。
 * <p>
 * 状态机：PENDING → SENT → EXECUTED / FAILED；
 * 若设备无活跃连接则直接 NO_SESSION；已下发但超时未回执则 TIMEOUT。
 */
@Data
public class DeviceCommand {

    /** 指令唯一 ID（云端生成，随下行报文带给设备，用于回执关联） */
    private String id;
    /** 目标设备 ID */
    private String deviceId;
    /** 动作：FAN_ON / FAN_OFF / VALVE_OPEN / VALVE_CLOSE / LAMP_ON / LAMP_OFF / PUMP_ON / PUMP_OFF */
    private String action;
    /** 附加参数（可空） */
    private Map<String, Object> params;
    /** 生命周期状态 */
    private String status;
    /** 下发来源：user / auto-rule（预留） */
    private String source = "user";

    private long issuedAt;
    private Long sentAt;
    private Long ackedAt;

    /** 设备回执携带的执行后状态（如 {fan:1, valve:0,...}） */
    private Map<String, Object> result;
    /** 备注（如超时/失败原因） */
    private String message;

    public static DeviceCommand create(String deviceId, String action, Map<String, Object> params) {
        DeviceCommand c = new DeviceCommand();
        c.setDeviceId(deviceId);
        c.setAction(action);
        c.setParams(params);
        c.setStatus("PENDING");
        c.setIssuedAt(System.currentTimeMillis());
        c.setResult(new LinkedHashMap<>());
        return c;
    }
}
