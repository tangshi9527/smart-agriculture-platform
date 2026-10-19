package com.smartagri.auth;

import com.smartagri.common.Result;
import com.smartagri.gateway.idempotent.Idempotent;
import com.smartagri.gateway.ratelimit.KeyType;
import com.smartagri.gateway.ratelimit.RateLimit;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RateLimit(capacity = 20, refillTokens = 20, refillSeconds = 60, keyType = KeyType.IP)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Idempotent(keyPrefix = "register")
    public Result<Void> register(@RequestBody Map<String, String> body) {
        authService.register(body.get("username"), body.get("password"));
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return Result.ok(authService.login(body.get("username"), body.get("password")));
    }
}
