package com.acme.resume.refresh.monster;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.monster")
public record MonsterProperties(String username, String password) {}
