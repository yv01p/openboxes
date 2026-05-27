<%@ page contentType="text/html;charset=UTF-8" %>
<html><head><title><g:message code="auth.forgotPassword.label" default="Forgot Password"/></title></head>
<body>
<g:form action="forgotPassword" method="post">
  <h2><g:message code="auth.forgotPassword.label" default="Forgot Password"/></h2>
  <p><g:message code="auth.forgotPassword.description" default="Enter your email to receive a reset link."/></p>
  <label><g:message code="user.email.label" default="Email"/>: <g:textField name="email"/></label>
  <g:submitButton name="submit" value="${message(code: 'default.button.submit.label', default: 'Submit')}"/>
</g:form>
</body></html>