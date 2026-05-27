package org.openboxes.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${openboxes.mail.from}")
    private String fromAddress;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendWelcomeEmail(String to, String username) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject("Welcome to OpenBoxes");
        message.setText(String.format(
            "Hello %s,\n\nWelcome to OpenBoxes! Your account has been successfully created.\n\nBest regards,\nThe OpenBoxes Team",
            username
        ));
        mailSender.send(message);
    }

    public void sendPasswordResetEmail(String to, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject("Reset Your OpenBoxes Password");
        message.setText(String.format(
            "Hello,\n\nYou requested a password reset. Click the link below to reset your password:\n\n%s\n\nIf you did not request this, please ignore this email.\n\nBest regards,\nThe OpenBoxes Team",
            resetLink
        ));
        mailSender.send(message);
    }
}
