package com.acme.resume.refresh.instahyre.exchange;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginRequest(@JsonProperty("email") String username, String password) {
}
