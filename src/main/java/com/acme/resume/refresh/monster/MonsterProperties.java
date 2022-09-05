package com.acme.resume.refresh.monster;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.validation.constraints.NotEmpty;

@ConfigurationProperties("app.monster")
public record MonsterProperties(@NotEmpty String username, @NotEmpty String password) {}
