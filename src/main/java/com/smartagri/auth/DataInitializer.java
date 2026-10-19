package com.smartagri.auth;

import com.smartagri.alert.AlertCondition;
import com.smartagri.alert.AlertRule;
import com.smartagri.alert.AlertRuleRepository;
import com.smartagri.alert.AlertService;
import com.smartagri.device.DeviceKey;
import com.smartagri.device.DeviceKeyRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时初始化默认账户 admin/admin123，预置若干示例设备接入密钥，
 * 并在规则表为空时预置两条默认告警规则（避免「规则管理」首屏为空）。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DeviceKeyRepository keyRepository;
    private final AlertRuleRepository ruleRepository;
    private final AlertService alertService;
    private static final SecureRandom RND = new SecureRandom();
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           DeviceKeyRepository keyRepository, AlertRuleRepository ruleRepository,
                           AlertService alertService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.keyRepository = keyRepository;
        this.ruleRepository = ruleRepository;
        this.alertService = alertService;
    }

    @Override
    public void run(String... args) {
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setRole("ADMIN");
            userRepository.save(admin);
        }
        // 预置 10 个示例设备密钥（真实设备接入即可用）
        for (int i = 1; i <= 10; i++) {
            String id = String.format("device-%05d", i);
            if (keyRepository.findById(id).isEmpty()) {
                keyRepository.save(DeviceKey.builder()
                        .deviceId(id)
                        .secret(randomSecret(24))
                        .status("ENABLED")
                        .remark("示例设备")
                        .createdAt(System.currentTimeMillis())
                        .build());
            }
        }
        seedDefaultRules();
    }

    /** 规则表为空时预置默认规则，写库即触发引擎热加载。 */
    private void seedDefaultRules() {
        if (ruleRepository.count() > 0) {
            return;
        }
        // 单条件 + 即时触发
        saveRule("高温告警",
                List.of(new AlertCondition("temperature", "GT", 35.0)), "AND", 0, "CRITICAL");
        saveRule("低土壤湿度告警",
                List.of(new AlertCondition("soilMoisture", "LT", 25.0)), "AND", 0, "WARN");
        // 多条件(AND) + 持续时长：演示「高温且高湿持续 8s 才告警」（组合概率足够高，可规律触发）
        saveRule("闷热组合告警",
                List.of(new AlertCondition("temperature", "GT", 24.0),
                        new AlertCondition("humidity", "GT", 60.0)),
                "AND", 8, "WARN");
    }

    private void saveRule(String name, List<AlertCondition> conds, String logic,
                          int durationSec, String severity) {
        AlertRule r = new AlertRule();
        r.setName(name);
        r.setDeviceId("");
        r.setConditions(new ArrayList<>(conds));
        r.setLogic(logic);
        r.setDurationSec(durationSec);
        r.setSeverity(severity);
        r.setEnabled(true);
        alertService.saveRule(r);
    }

    private static String randomSecret(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARS.charAt(RND.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
