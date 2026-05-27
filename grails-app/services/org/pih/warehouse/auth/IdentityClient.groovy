package org.pih.warehouse.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

class IdentityClient {
    private final RestTemplate restTemplate

    @Value('${openboxes.identity.base-url:http://identity-service:8082}')
    String identityBaseUrl

    IdentityClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory()
        factory.connectTimeout = 5000   // ms
        factory.readTimeout = 10000     // ms
        this.restTemplate = new RestTemplate(factory)
    }

    /** Returns [body: Map, setCookieHeader: String]. Throws BadCredentialsException/AccountDisabledException on 401/403. */
    Map login(String username, String password, String locationId) {
        try {
            HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON
            HttpEntity<Map> req = new HttpEntity<>([username: username, password: password, location: locationId], h)
            ResponseEntity<Map> resp = restTemplate.postForEntity("${identityBaseUrl}/api/identity/login", req, Map)
            return [body: resp.body, setCookieHeader: resp.headers.getFirst('Set-Cookie')]
        } catch (HttpClientErrorException e) {
            // Grails 3 / Spring 4.3.30: nested HttpClientErrorException.Unauthorized/Forbidden subclasses don't exist (added in Spring 5.0).
            if (e.statusCode == HttpStatus.UNAUTHORIZED) throw new BadCredentialsException(e.responseBodyAsString)
            if (e.statusCode == HttpStatus.FORBIDDEN) throw new AccountDisabledException(e.responseBodyAsString)
            throw e
        }
    }

    String logout(String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.add('Cookie', "obx_token=${obxTokenCookie}")
        ResponseEntity<Map> resp = restTemplate.exchange("${identityBaseUrl}/api/identity/logout",
            HttpMethod.POST, new HttpEntity<>(h), Map)
        return resp.headers.getFirst('Set-Cookie')   // the clear-cookie header
    }

    Map chooseLocation(String locationId, String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.add('Cookie', "obx_token=${obxTokenCookie}")
        ResponseEntity<Map> resp = restTemplate.exchange("${identityBaseUrl}/api/identity/chooseLocation/${locationId}",
            HttpMethod.PUT, new HttpEntity<>(h), Map)
        return [body: resp.body, setCookieHeader: resp.headers.getFirst('Set-Cookie')]
    }

    Map me(String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.add('Cookie', "obx_token=${obxTokenCookie}")
        return restTemplate.exchange("${identityBaseUrl}/api/identity/me",
            HttpMethod.GET, new HttpEntity<>(h), Map).body
    }

    Map signup(Map signupData) {
        try {
            HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON
            return restTemplate.postForEntity("${identityBaseUrl}/api/identity/signup",
                new HttpEntity<>(signupData, h), Map).body
        } catch (HttpClientErrorException e) {
            if (e.statusCode == HttpStatus.FORBIDDEN) throw new SignupDisabledException(e.responseBodyAsString)
            if (e.statusCode == HttpStatus.CONFLICT) throw new DuplicateUsernameException(e.responseBodyAsString)
            if (e.statusCode == HttpStatus.BAD_REQUEST) throw new ValidationException(e.responseBodyAsString)
            throw e
        }
    }

    void changePassword(String currentPassword, String newPassword, String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON; h.add('Cookie', "obx_token=${obxTokenCookie}")
        try {
            restTemplate.postForEntity("${identityBaseUrl}/api/identity/password/change",
                new HttpEntity<>([currentPassword: currentPassword, newPassword: newPassword], h), Map)
        } catch (HttpClientErrorException e) {
            if (e.statusCode == HttpStatus.UNAUTHORIZED) throw new BadCredentialsException(e.responseBodyAsString)
            if (e.statusCode == HttpStatus.BAD_REQUEST) throw new PasswordTooWeakException(e.responseBodyAsString)
            throw e
        }
    }

    void changeUserPasswordAsAdmin(String userId, String newPassword, String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON; h.add('Cookie', "obx_token=${obxTokenCookie}")
        restTemplate.exchange("${identityBaseUrl}/api/identity/users/${userId}/password",
            HttpMethod.PUT, new HttpEntity<>([newPassword: newPassword], h), Map)
    }

    void requestPasswordReset(String email) {
        HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON
        restTemplate.postForEntity("${identityBaseUrl}/api/identity/password/reset-request",
            new HttpEntity<>([email: email], h), Map)
        // always 200; never throws
    }

    void resetPassword(String token, String newPassword) {
        HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON
        try {
            restTemplate.postForEntity("${identityBaseUrl}/api/identity/password/reset/${token}",
                new HttpEntity<>([newPassword: newPassword], h), Map)
        } catch (HttpClientErrorException e) {
            if (e.statusCode == HttpStatus.BAD_REQUEST) throw new InvalidTokenException(e.responseBodyAsString)
            throw e
        }
    }
}
