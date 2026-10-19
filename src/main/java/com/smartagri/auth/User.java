package com.smartagri.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台用户（认证主体）。默认使用 H2 内嵌库，可平滑切换为 MySQL。
 */
@Data
@Entity
@Table(name = "t_user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String username;

    @Column(nullable = false)
    private String password; // BCrypt 密文

    @Column(length = 32)
    private String role = "USER";

    private LocalDateTime createdAt = LocalDateTime.now();
}
