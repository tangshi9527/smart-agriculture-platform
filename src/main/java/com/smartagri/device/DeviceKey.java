package com.smartagri.device;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备接入密钥：真实设备接入 MQTT 时须携带与 clientId 一致的 username 与对应的 secret，
 * 平台校验通过后才允许注册；校验失败直接拒绝连接。
 * 模拟器则使用独立的「服务账号」(simulator / 共享密钥) 一次性放行。
 */
@Entity
@Table(name = "t_device_key")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceKey {

    @Id
    private String deviceId;

    private String secret;

    /** ENABLED / DISABLED */
    private String status;

    private String remark;

    private Long createdAt;
}
