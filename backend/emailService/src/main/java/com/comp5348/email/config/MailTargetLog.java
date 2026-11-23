package com.comp5348.email.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says at startup where mail goes. Since A11 compose and kind send to Mailpit unless MAIL_HOST says otherwise,
 * so a backend/.env that only has the Gmail address and app password (the setup before A11) silently stops
 * sending real mail. The WARN makes that visible (review 51 🟡5). The password is never logged.
 */
@Component
public class MailTargetLog {

    private static final Logger logger = LoggerFactory.getLogger(MailTargetLog.class);

    private final String host;
    private final String port;
    private final String username;

    public MailTargetLog(@Value("${spring.mail.host:}") String host,
                         @Value("${spring.mail.port:}") String port,
                         @Value("${spring.mail.username:}") String username) {
        this.host = host;
        this.port = port;
        this.username = username;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logMailTarget() {
        logger.info("Mail goes to {}:{}", host, port);
        if ("mailpit".equalsIgnoreCase(host) && username != null && !username.isBlank()) {
            logger.warn("MAIL_USERNAME is set but mail goes to Mailpit, not a real mail server. To send real mail, "
                    + "set MAIL_HOST, MAIL_PORT, MAIL_SMTP_AUTH and MAIL_SMTP_STARTTLS in backend/.env");
        }
    }
}
