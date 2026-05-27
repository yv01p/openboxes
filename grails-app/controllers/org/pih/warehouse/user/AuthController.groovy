/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.user

import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import org.pih.warehouse.auth.AccountDisabledException
import org.pih.warehouse.auth.BadCredentialsException
import org.pih.warehouse.auth.DuplicateUsernameException
import org.pih.warehouse.auth.InvalidTokenException
import org.pih.warehouse.auth.JwtService
import org.pih.warehouse.auth.PasswordTooWeakException
import org.pih.warehouse.auth.SignupDisabledException
import org.pih.warehouse.auth.ValidationException
import org.pih.warehouse.core.MailService
import org.pih.warehouse.core.User

class AuthController {

    MailService mailService
    def userService
    def authService
    def jwtService
    def identityClient
    GrailsApplication grailsApplication
    def recaptchaService
    def userAgentIdentService

    static allowedMethods = [login: "GET", doLogin: "POST", logout: "GET"]

    /**
     * Show index page - just a redirect to the list page.
     */
    def index() {
        redirect(action: "login", params: params)
    }

    /**
     * Checks whether there is an authenticated user in the session.
     */
    def authorized() {
        if (session.user == null) {
            flash.message = "${warehouse.message(code: 'auth.notAuthorized.message')}"
            redirect(controller: 'auth', action: 'login')
        }
    }

    /**
     * Allows user to log into the system.
     */
    def login() {
        if (session.user) {
            flash.message = "You have already logged in."
            redirect(controller: "dashboard", action: "index")
        }

        if (userAgentIdentService.isMobile()) {
            redirect(controller: "mobile", action: "login")
            return
        }

    }


    /**
     * Performs the authentication logic.
     */
    def handleLogin() {
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            try {
                def result = identityClient.login(params.username, params.password, null)
                def userInstance = User.findByUsernameOrEmail(params.username, params.username)
                session.user = userInstance
                session.userName = userInstance.username
                if (userInstance?.warehouse && userInstance?.rememberLastLocation) session.warehouse = userInstance.warehouse
                response.setHeader('Set-Cookie', result.setCookieHeader)
                if (session?.targetUri) { redirect(uri: session.targetUri); session.targetUri = null; return }
                redirect(controller: 'dashboard', action: 'index')
            } catch (BadCredentialsException e) {
                flash.message = "${warehouse.message(code: 'auth.incorrectPassword.label', args: [params.username])}"
                def userInstance = new User(username: params['username'])
                userInstance.errors.rejectValue("version", "default.authentication.failure",
                    [warehouse.message(code: 'user.label')] as Object[], "${warehouse.message(code: 'auth.unableToAuthenticateUser.message')}")
                render(view: "login", model: [userInstance: userInstance])
            } catch (AccountDisabledException e) {
                flash.message = "${warehouse.message(code: 'auth.accountRequestUnderReview.message')}"
                redirect(controller: 'auth', action: 'login')
            }
        }
    }


    /**
     * Allows user to log out of the system
     */
    def logout() {
        def tokenCookie = request.cookies?.find { it.name == 'obx_token' }?.value
        String clearHeader = tokenCookie ? identityClient.logout(tokenCookie) : 'obx_token=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0'
        response.setHeader('Set-Cookie', clearHeader)
        if (session.impersonateUserId) {
            session.user = User.get(session.activeUserId); session.impersonateUserId = null; session.activeUserId = null
        } else {
            session.invalidate()
        }
        redirect(controller: 'auth', action: 'login')
    }


    /**
     * Allow user to register a new account
     */
    def signup() {
        Boolean enabled = grailsApplication.config.openboxes.signup.enabled?:false
        if (!enabled) {
            flash.message = "Apologies, but the signup feature is disabled on your system. " +
                    "Please contact a system administrator for access."
            redirect(controller: "auth", action: "login")
        }
        Boolean configured = grailsApplication.config.openboxes.signup.recaptcha.v2.secretKey?.trim()
        if (!configured) {
            flash.message = "Apologies, but reCAPTCHA is not set up on this system. " +
                    "Please contact a system administrator for access."
            redirect(controller: "auth", action: "login")
        }
    }

    /**
     * Handle account registration.
     */
    @Transactional
    def handleSignup() {
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            try {
                def signupData = [
                    username: params.email,
                    password: params.password,
                    firstName: params.firstName,
                    lastName: params.lastName,
                    email: params.email,
                    recaptchaToken: params["g-recaptcha-response"]
                ]
                identityClient.signup(signupData)
                flash.message = "${warehouse.message(code: 'auth.accountRequestSubmitted.message', default: 'Account request submitted; pending activation.')}"
                redirect(action: 'login')
            } catch (SignupDisabledException e) {
                flash.error = "${warehouse.message(code: 'auth.signupDisabled.message', default: 'Signup is currently disabled.')}"
                redirect(action: 'signup')
            } catch (DuplicateUsernameException e) {
                flash.error = "${warehouse.message(code: 'auth.duplicateUsername.message', default: 'Username or email already exists.')}"
                render(view: 'signup', model: [params: params])
            } catch (ValidationException e) {
                flash.error = e.message
                render(view: 'signup', model: [params: params])
            }
        }
    }


    def forgotPassword() {   // GET renders form; POST shims to identity-service
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            identityClient.requestPasswordReset(params.email)
            flash.message = "${warehouse.message(code: 'auth.passwordResetRequestSent.message', default: 'If that email exists, a reset link has been sent.')}"
            redirect(action: 'login')
        }
        // GET: just render forgotPassword.gsp
    }

    def resetPassword() {   // GET renders form (with token in model); POST shims to identity-service
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            try {
                identityClient.resetPassword(params.token, params.newPassword)
                flash.message = "${warehouse.message(code: 'auth.passwordResetSuccess.message', default: 'Password reset.')}"
                redirect(action: 'login')
            } catch (InvalidTokenException e) {
                flash.error = "Reset link invalid or expired."
                redirect(action: 'login')
            } catch (PasswordTooWeakException e) {
                flash.error = "Password does not meet complexity requirements."
                render(view: 'resetPassword', model: [token: params.token])
            }
            return
        }
        render(view: 'resetPassword', model: [token: params.token])
    }

    def renderAccountCreatedEmail() {
        def userInstance = User.get(params.id)
        render(template: "/email/userAccountCreated", model: [userInstance: userInstance])
    }

    def renderAccountConfirmedEmail() {
        def userInstance = User.get(params.id)
        render(template: "/email/userAccountConfirmed", model: [userInstance: userInstance])
    }
}
