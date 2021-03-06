package devCamp.WebApp;

/*******************************************************************************
 * Copyright © Microsoft Open Technologies, Inc.
 *
 * All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * THIS CODE IS PROVIDED *AS IS* BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION
 * ANY IMPLIED WARRANTIES OR CONDITIONS OF TITLE, FITNESS FOR A
 * PARTICULAR PURPOSE, MERCHANTABILITY OR NON-INFRINGEMENT.
 *
 * See the Apache License, Version 2.0 for the specific language
 * governing permissions and limitations under the License.
 ******************************************************************************/
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.naming.ServiceUnavailableException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.microsoft.aad.adal4j.AuthenticationContext;
import com.microsoft.aad.adal4j.AuthenticationResult;
import com.microsoft.aad.adal4j.ClientCredential;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.openid.connect.sdk.AuthenticationErrorResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationResponseParser;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;

import devCamp.WebApp.Utils.AuthHelper;

public class AzureADResponseFilter extends OncePerRequestFilter {

    private static Logger log = LoggerFactory.getLogger(AzureADResponseFilter.class);

    public static final String clientId = "9f9967cf-2f4c-4413-9075-3b5b6bbd90dd";
    public static final String clientSecret = "cS8aIzFM3XgsCEAmE0ctVio6ySjOwmQp25q9RXBYtr4=";
    public static final String tenant = "86bea8f4-503f-46f2-ba4e-befba8ae383a";
    public static final String authority = "https://login.microsoftonline.com/";
    
    private String csrfToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {


        	String currentUri = request.getScheme() + "://" + request.getServerName()
                    + ("http".equals(request.getScheme()) && request.getServerPort() == 80
                            || "https".equals(request.getScheme()) && request.getServerPort() == 443 ? ""
                                    : ":" + request.getServerPort())
                    + request.getRequestURI();

            String fullUrl = currentUri + (request.getQueryString() != null ? "?" + request.getQueryString() : "");

            log.info("URL: " + fullUrl);

            csrfToken = null;

            // check if user has a session
            if (!AuthHelper.isAuthenticated(request) && AuthHelper.containsAuthenticationData(request)) {

                // when not authenticated and the response contains authentication data,
                // this request came from AzureAD login page.

                log.info("AuthHelper.isAuthenticated = false && AuthHelper.containsAuthenticationData = true");

                Map<String, String> params = new HashMap<String, String>();
                for (String key : request.getParameterMap().keySet()) {
                    params.put(key, request.getParameterMap().get(key)[0]);
                }

                AuthenticationResponse authResponse = AuthenticationResponseParser.parse(new URI(fullUrl), params);
                log.info("authResponse = " + authResponse);

                if (AuthHelper.isAuthenticationSuccessful(authResponse)) {

                    // when authentication result from Azure AD is success,
                    // retrieve the state (which is our csrf token) and store it to request header.
                    // spring csrf filter reads this token in request header.

                    log.info("AuthHelper.isAuthenticationSuccessful = true");

                    AuthenticationSuccessResponse oidcResponse = (AuthenticationSuccessResponse) authResponse;
                    AuthenticationResult result = getAccessToken(oidcResponse.getAuthorizationCode(), currentUri);

                    // the state is our csrf token.
                    log.info("state = " + oidcResponse.getState());
                    csrfToken = oidcResponse.getState().getValue();

                    // we want to set csrf token to "request" header for Spring CsrfFilter.
//                    response.setHeader("X-CSRF-TOKEN", csrfToken);
//                    log.info("set csrf token to response header");

                    // store authenticated principal to spring security context holder.
                    Authentication anAuthentication = new PreAuthenticatedAuthenticationToken(result.getUserInfo(), null);
                    anAuthentication.setAuthenticated(true);
                    SecurityContextHolder.getContext().setAuthentication(anAuthentication);

                    log.info("SecurityContextHolder.getContext().getAuthentication() = " + SecurityContextHolder.getContext().getAuthentication());

                    // store authentication data to Azure AD API. (in session)
                    createSessionPrincipal(request, result);
                } else {
                    log.info("AuthHelper.isAuthenticationSuccessful = false");

                    AuthenticationErrorResponse oidcResponse = (AuthenticationErrorResponse) authResponse;
                    throw new Exception(String.format("Request for auth code failed: %s - %s",
                            oidcResponse.getErrorObject().getCode(),
                            oidcResponse.getErrorObject().getDescription()));
                }
            }
        } catch (Throwable exc) {
            response.setStatus(500);
            request.setAttribute("error", exc.getMessage());
            response.sendRedirect(((HttpServletRequest) request).getContextPath() + "/error.jsp");
        }

        if (csrfToken != null) {
            // if required, set csrf token to request header.
            log.info("create a dummy request and put csrf token in its header {}", csrfToken);
            filterChain.doFilter(new HttpServletRequestWrapper(request) {

                @Override
                public String getHeader(String name) {
                    if ("X-CSRF-TOKEN".equals(name)) {
                        log.info("read csrf token from request header: {}", csrfToken);
                        log.info("   request method {}", request.getMethod());
                        return csrfToken;
                    }
                    return super.getHeader(name);
                }
                @Override
                public String getMethod() {
                	return "GET";
                }
            }, response);
        } else {
            // in regular cases, do nothing.
            log.info("continue on with filter");
            filterChain.doFilter(request, response);
        }
    }

    private AuthenticationResult getAccessToken(AuthorizationCode authorizationCode, String currentUri)
            throws Throwable {
        String authCode = authorizationCode.getValue();
        ClientCredential credential = new ClientCredential(clientId, clientSecret);
        AuthenticationContext context = null;
        AuthenticationResult result = null;
        ExecutorService service = null;
        try {
            service = Executors.newFixedThreadPool(1);
            context = new AuthenticationContext(authority + tenant + "/", true, service);
            Future<AuthenticationResult> future = context.acquireTokenByAuthorizationCode(authCode, new URI(currentUri),
                    credential, null);
            result = future.get();
        } catch (ExecutionException e) {
            throw e.getCause();
        } finally {
            service.shutdown();
        }

        if (result == null) {
            throw new ServiceUnavailableException("authentication result was null");
        }
        return result;
    }

    private void createSessionPrincipal(HttpServletRequest httpRequest, AuthenticationResult result) throws Exception {

        log.info("create session principal: " + result.getUserInfo().getDisplayableId());

        httpRequest.getSession().setAttribute(AuthHelper.PRINCIPAL_SESSION_NAME, result);
    }

}
