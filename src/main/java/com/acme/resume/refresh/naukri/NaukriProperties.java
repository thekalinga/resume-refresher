package com.acme.resume.refresh.naukri;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.validation.constraints.NotEmpty;

@ConfigurationProperties("app.naukri")
public record NaukriProperties(@NotEmpty String username, @NotEmpty String password) {}
