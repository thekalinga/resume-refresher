package com.acme.resume.refresh.naukri.exchange;

public record LoginRequest(String username, String password, boolean isLoginByEmail) {
}
