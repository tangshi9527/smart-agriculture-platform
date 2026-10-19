package com.smartagri.mq.consumer;

import com.smartagri.influx.InfluxService;
import com.smartagri.mq.TelemetryConsumer;
import com.smartagri.mq.TelemetryEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 持久化消费者：将遥测事件写入时序库（InfluxDB），失败自动降级为内存存储。
 * 与接入层解耦后，即使时序库慢或短暂不可用，也不会阻塞设备接入与告警 / 推送链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageTelemetryConsumer implements TelemetryConsumer {

    private final InfluxService influx;

    @Override
    public String name() {
        return "storage";
    }

    @Override
    public void consume(TelemetryEvent event) {
        try {
            influx.write(event.toTelemetry());
        } catch (Exception e) {
            log.error("遥测持久化失败 device={}: {}", event.getDeviceId(), e.getMessage());
        }
    }
}
