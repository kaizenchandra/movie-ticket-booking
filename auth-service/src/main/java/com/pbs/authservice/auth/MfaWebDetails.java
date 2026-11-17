package com.pbs.authservice.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

public class MfaWebDetails extends WebAuthenticationDetails {
    private String code, recoveryCode;
    private String amr = "pwd";

    public MfaWebDetails(HttpServletRequest request) {
        super(request);
        code = request.getParameter("code");
        recoveryCode = request.getParameter("recoveryCode");
    }

    public String code() {
        return code;
    }

    public String recoveryCode() {
        return recoveryCode;
    }

    public String amr() {
        return amr;
    }

    public void verified(String value) {
        amr = value;
    }

    public void erase() {
        code = null;
        recoveryCode = null;
    }
}
