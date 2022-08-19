package com.acme.resume.refresh.instahyre;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.instahyre")
public record InstahyreProperties(String username, String password) {}
