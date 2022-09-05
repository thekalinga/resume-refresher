package com.acme.resume.refresh.instahyre;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.validation.constraints.NotEmpty;

@ConfigurationProperties("app.instahyre")
public record InstahyreProperties(@NotEmpty String username, @NotEmpty String password) {}
