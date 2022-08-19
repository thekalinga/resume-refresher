package com.acme.resume.refresh.instahyre.exchange;

import com.fasterxml.jackson.annotation.JsonProperty;

//calculate_opps":true,"candidate":"/api/v1/limited_candidate/<candidateId>","resource_uri":"/api/v1/resume/<resumeId>","file_b64":"data:application/pdf;base64,<base64 content>","title
public record UploadResumeRequest(@JsonProperty("candidate") String candidateUri,
                                  @JsonProperty("resource_uri") String resumeUri,
                                  @JsonProperty("title") String filename,
                                  @JsonProperty("file_b64") String base64FileContent,
                                  @JsonProperty("calculate_opps") boolean calculateOpportunities) {
}
