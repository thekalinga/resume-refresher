package com.acme.resume.refresh.naukri;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.naukri")
public record NaukriProperties(String username, String password) {}
