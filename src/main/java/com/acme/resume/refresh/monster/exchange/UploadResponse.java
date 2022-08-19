package com.acme.resume.refresh.monster.exchange;

import com.acme.resume.refresh.monster.exchange.UploadResumeUploadDetailedStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

public record UploadResponse(int uploadResumeStatus, String uploadResumeStatusText, @JsonProperty("uploadResumeResponse") UploadResumeUploadDetailedStatus additionalDetails) {
}
