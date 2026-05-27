<%@ page contentType="text/html;charset=UTF-8" %>
<html><head><title><g:message code="auth.resetPassword.label" default="Reset Password"/></title></head>
<body>
<g:form action="resetPassword" method="post">
  <h2><g:message code="auth.resetPassword.label" default="Reset Password"/></h2>
  <g:hiddenField name="token" value="${token}"/>
  <label><g:message code="user.newPassword.label" default="New Password"/>: <g:passwordField name="newPassword"/></label>
  <g:submitButton name="submit" value="${message(code: 'default.button.submit.label', default: 'Submit')}"/>
</g:form>
</body></html>