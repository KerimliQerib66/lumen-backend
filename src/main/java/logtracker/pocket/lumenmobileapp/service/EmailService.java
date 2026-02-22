package logtracker.pocket.lumenmobileapp.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    // Resend requires the "From" address to be a verified domain, e.g., "onboarding@resend.dev"
    // or your own verified domain. You cannot use "resend" as the email address.
    @Value("${spring.mail.username:onboarding@resend.dev}")
    private String fromEmail;


    public void sendHtmlEmail(String to, String subject, String templateName, Map<String, Object> variables) {
        log.info("Attempting to send email to {} with subject: {}", to, subject);
        try {
            Context context = new Context();
            if (variables != null) {
                context.setVariables(variables);
            }
            String htmlContent = templateEngine.process(templateName, context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Email successfully sent to {} with subject: {}", to, subject);
        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error sending email to {}: {}", to, e.getMessage(), e);
        }
    }
}
