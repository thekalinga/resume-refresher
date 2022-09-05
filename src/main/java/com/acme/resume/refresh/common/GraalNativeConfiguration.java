package com.acme.resume.refresh.common;

import com.acme.resume.refresh.instahyre.exchange.CandidateResponse;
import com.acme.resume.refresh.instahyre.exchange.ResumeResponse;
import com.acme.resume.refresh.instahyre.exchange.SessionIdAndCsrfToken;
import com.acme.resume.refresh.instahyre.exchange.UploadResumeRequest;
import com.acme.resume.refresh.monster.exchange.InitialCookieAndRedirectUrl;
import com.acme.resume.refresh.monster.exchange.PersonalDetailSection;
import com.acme.resume.refresh.monster.exchange.PersonalDetails;
import com.acme.resume.refresh.monster.exchange.UploadResponse;
import com.acme.resume.refresh.monster.exchange.UploadResumeUploadDetailedStatus;
import com.acme.resume.refresh.monster.exchange.UserProfile;
import com.acme.resume.refresh.monster.exchange.UserProfileResponse;
import com.acme.resume.refresh.naukri.exchange.AdvertiseResumeRequest;
import com.acme.resume.refresh.naukri.exchange.Cookie;
import com.acme.resume.refresh.naukri.exchange.Dashboard;
import com.acme.resume.refresh.naukri.exchange.DashboardResponse;
import com.acme.resume.refresh.naukri.exchange.LoginRequest;
import com.acme.resume.refresh.naukri.exchange.LoginResponse;
import com.acme.resume.refresh.naukri.exchange.TextCv;
import org.springframework.nativex.hint.TypeAccess;
import org.springframework.nativex.hint.TypeHint;
import org.springframework.nativex.type.NativeConfiguration;

// hints are apparently required for reflective access to work (jackson) incase of webclient
// https://github.com/spring-projects-experimental/spring-native/tree/main/samples/webclient
// Refer to https://github.com/spring-projects-experimental/spring-native/issues/412
// Refer to https://github.com/spring-projects-experimental/spring-native/issues/1152
// Refer to https://docs.spring.io/spring-native/docs/0.12.x/reference/htmlsingle/
@TypeHint(types = {
    // naukri
    AdvertiseResumeRequest.class,
    Cookie.class,
    Dashboard.class,
    DashboardResponse.class,
    LoginRequest.class,
    LoginResponse.class,
    TextCv.class,
    InitialCookieAndRedirectUrl.class,
    // monster
    com.acme.resume.refresh.monster.exchange.LoginRequest.class,
    com.acme.resume.refresh.monster.exchange.LoginResponse.class,
    PersonalDetails.class,
    PersonalDetailSection.class,
    UploadResponse.class,
    UploadResumeUploadDetailedStatus.class,
    UserProfile.class,
    UserProfileResponse.class,
    // instahyre
    CandidateResponse.class,
    com.acme.resume.refresh.instahyre.exchange.LoginRequest.class,
    ResumeResponse.class,
    SessionIdAndCsrfToken.class,
    UploadResumeRequest.class
}, access = { TypeAccess.DECLARED_CONSTRUCTORS, TypeAccess.PUBLIC_METHODS })

//Disabling file logging due to this bug in native image builder wrt RandomAccessFile
//https://github.com/oracle/graal/issues/2723
//@NativeHint(trigger = org.apache.logging.log4j.core.Logger.class,
//              initialization = {
//                @InitializationHint(typeNames = {
//  //                  "java.io.RandomAccessFile",
//                    "com.lmax.disruptor.RingBuffer",
//                    "com.lmax.disruptor.Sequence",
//                    "com.lmax.disruptor.MultiProducerSequencer",
//                    "org.springframework.boot.logging.log4j2.ColorConverter"
//                }, initTime = InitializationTime.BUILD),
//                @InitializationHint(typeNames = {"org.apache.logging.log4j.core.util.Log4jThread"}, initTime = InitializationTime.BUILD)
//             }
//)
// Made available to spring aot plugin via META-INF/spring.factories (service loading)
public class GraalNativeConfiguration implements NativeConfiguration {
}
