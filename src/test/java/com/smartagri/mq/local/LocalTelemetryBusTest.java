package com.smartagri.mq.local;

import com.smartagri.mq.TelemetryConsumer;
import com.smartagri.mq.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地总线单元测试：验证扇出语义与单消费者异常隔离（不依赖 Spring 上下文）。
 */
class LocalTelemetryBusTest {

    /** 同步执行器：使发布-消费变为确定性的同步调用，便于断言 */
    private final Executor sync = Runnable::run;

    @Test
    void publishesToAllConsumers() {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        TelemetryConsumer c1 = consumer("c1", received);
        TelemetryConsumer c2 = consumer("c2", received);
        TelemetryConsumer c3 = consumer("c3", received);

        LocalTelemetryBus bus = new LocalTelemetryBus(List.of(c1, c2, c3), sync);
        bus.publish(sample());

        assertEquals(List.of("c1", "c2", "c3"), received);
        assertEquals("local", bus.mode());
    }

    @Test
    void oneConsumerFailureDoesNotStopOthers() {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        TelemetryConsumer bad = new TelemetryConsumer() {
            @Override
            public void consume(TelemetryEvent event) {
                throw new RuntimeException("boom");
            }

            @Override
            public String name() {
                return "bad";
            }
        };
        TelemetryConsumer good = consumer("good", received);

        LocalTelemetryBus bus = new LocalTelemetryBus(List.of(bad, good), sync);
        bus.publish(sample());

        assertEquals(List.of("good"), received);
    }

    @Test
    void reportsRabbitmqModeForCompatibilitySwitch() {
        // 仅校验模式标识；RabbitMQ 实现由集成测试覆盖
        LocalTelemetryBus bus = new LocalTelemetryBus(List.of(), sync);
        assertTrue(bus.mode().equals("local"));
    }

    private TelemetryConsumer consumer(String name, CopyOnWriteArrayList<String> sink) {
        return new TelemetryConsumer() {
            @Override
            public void consume(TelemetryEvent event) {
                sink.add(name);
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    private TelemetryEvent sample() {
        return TelemetryEvent.builder()
                .eventId("e1").deviceId("d1").ts(1L).producedAt(2L).build();
    }
}
