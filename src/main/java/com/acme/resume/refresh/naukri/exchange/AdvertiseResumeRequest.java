package com.acme.resume.refresh.naukri.exchange;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdvertiseResumeRequest(@JsonProperty("textCV") TextCv textCv) {
}
