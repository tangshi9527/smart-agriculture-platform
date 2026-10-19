package com.smartagri.controller;

import com.smartagri.common.Result;
import com.smartagri.device.DeviceKey;
import com.smartagri.device.DeviceKeyRepository;
import com.smartagri.device.DeviceShadowService;
import com.smartagri.gateway.ApiVersion;
import com.smartagri.gateway.idempotent.Idempotent;
import com.smartagri.gateway.ratelimit.KeyType;
import com.smartagri.gateway.ratelimit.RateLimit;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备与设备影子查询接口，并提供设备接入密钥的生成/重置/停用。
 */
@RestController
@RequestMapping("/api/devices")
@ApiVersion(1)
public class DeviceController {

    private final DeviceShadowService shadow;
    private final DeviceKeyRepository keyRepository;
    private static final SecureRandom RND = new SecureRandom();
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public DeviceController(DeviceShadowService shadow, DeviceKeyRepository keyRepository) {
        this.shadow = shadow;
        this.keyRepository = keyRepository;
    }

    @GetMapping
    public Result<List<Map<Object, Object>>> list() {
        return Result.ok(shadow.getAllShadows());
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.ok(shadow.summary());
    }

    @GetMapping("/{id}/shadow")
    public Result<Map<Object, Object>> shadow(@PathVariable("id") String id) {
        return Result.ok(shadow.getShadow(id));
    }

    /** 设备执行器控制状态（fan/valve/lamp/pump 的开关） */
    @GetMapping("/{id}/controls")
    public Result<Map<String, Object>> controls(@PathVariable("id") String id) {
        return Result.ok(shadow.getControls(id));
    }

    /** 设备接入密钥列表 */
    @GetMapping("/keys")
    public Result<List<DeviceKey>> keys() {
        return Result.ok(keyRepository.findAll());
    }

    /** 生成 / 重置某设备的接入密钥（返回明文 secret，仅此一次） */
    @PostMapping("/{id}/key")
    @RateLimit(capacity = 5, refillTokens = 5, refillSeconds = 60, keyType = KeyType.USER)
    @Idempotent(keyPrefix = "gen-key")
    public Result<Map<String, Object>> genKey(@PathVariable("id") String id) {
        String secret = randomSecret(24);
        DeviceKey k = keyRepository.findById(id).orElse(null);
        if (k == null) {
            k = DeviceKey.builder().deviceId(id).build();
        }
        k.setSecret(secret);
        k.setStatus("ENABLED");
        k.setCreatedAt(System.currentTimeMillis());
        keyRepository.save(k);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", id);
        m.put("secret", secret);
        m.put("status", k.getStatus());
        return Result.ok(m);
    }

    /** 停用某设备密钥（接入将被拒绝） */
    @DeleteMapping("/{id}/key")
    @Idempotent(keyPrefix = "disable-key")
    public Result<Void> disableKey(@PathVariable("id") String id) {
        DeviceKey k = keyRepository.findById(id).orElse(null);
        if (k != null) {
            k.setStatus("DISABLED");
            keyRepository.save(k);
        }
        return Result.ok();
    }

    private static String randomSecret(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARS.charAt(RND.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
