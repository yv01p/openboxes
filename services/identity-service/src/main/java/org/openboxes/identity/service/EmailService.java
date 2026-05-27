package org.openboxes.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailService(JavaMailSender mailSender, @Value("${openboxes.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendWelcomeEmail(String to, String username) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Welcome to OpenBoxes");
        message.setText("Hi " + username + ",\n\nYour account has been created and is pending activation.\n\n— OpenBoxes");
        mailSender.send(message);
    }

    public void sendPasswordResetEmail(String to, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("OpenBoxes password reset");
        message.setText("To reset your password, follow this link (valid 24 hours):\n\n" + resetLink + "\n\n— OpenBoxes");
        mailSender.send(message);
    }
}
