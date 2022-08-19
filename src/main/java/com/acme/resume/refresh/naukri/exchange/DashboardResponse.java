package com.acme.resume.refresh.naukri.exchange;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DashboardResponse(@JsonProperty("dashBoard") Dashboard dashboard) {
}
