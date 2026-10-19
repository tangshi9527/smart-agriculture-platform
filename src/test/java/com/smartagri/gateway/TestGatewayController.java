package com.smartagri.gateway;

import com.smartagri.common.Result;
import com.smartagri.gateway.idempotent.Idempotent;
import com.smartagri.gateway.ratelimit.KeyType;
import com.smartagri.gateway.ratelimit.RateLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用于 API 治理 Web 测试的脚手架控制器：
 * 覆盖版本化（@ApiVersion）、限流（@RateLimit）、幂等（@Idempotent）三类能力。
 */
@RestController
@RequestMapping("/api/test")
@ApiVersion(1)
public class TestGatewayController {

    private final AtomicInteger counter = new AtomicInteger();

    @GetMapping("/ping")
    public Result<Integer> ping() {
        return Result.ok(counter.incrementAndGet());
    }

    @GetMapping("/limited")
    @RateLimit(capacity = 2, refillTokens = 2, refillSeconds = 60, keyType = KeyType.IP)
    public Result<String> limited() {
        return Result.ok("ok");
    }

    @PostMapping("/idem")
    @Idempotent(keyPrefix = "test")
    public Result<Integer> idem() {
        return Result.ok(counter.incrementAndGet());
    }
}
