package com.comp5348.email.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import com.comp5348.email.model.EmailOutbox;
import com.comp5348.email.repository.EmailOutboxRepository;
import java.util.Random;
import java.time.LocalDateTime;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;
    
    @Autowired
    private EmailOutboxRepository emailOutboxRepository;

    /**
     * 生成6位数字验证码
     */
    public String generateVerificationCode() {
        Random random = new Random();
        return String.format("%06d", random.nextInt(1000000));
    }

    /**
     * 发送注册验证码
     */
    public String sendRegistrationVerificationCode(String email) {
        // 生成验证码
        String code = generateVerificationCode();
        
        // 保存到数据库
        EmailOutbox emailOutbox = new EmailOutbox(email, code, "REGISTRATION");
        emailOutboxRepository.save(emailOutbox);
        
        // 发送邮件
        sendVerificationCode(email, code, "注册验证码");
        
        return code;
    }

    /**
     * 发送验证码邮件
     */
    public void sendVerificationCode(String to, String code, String type) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("您的" + type);
        
        String emailContent = String.format(
            "您好！\n\n" +
            "您的%s是：%s\n\n" +
            "此验证码将在5分钟后过期，请及时使用。\n\n" +
            "如果您没有请求此验证码，请忽略此邮件。\n\n" +
            "谢谢！",
            type, code
        );
        
        message.setText(emailContent);
        
        try {
            mailSender.send(message);
            // 更新状态为已发送
            EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(to, code, "PENDING");
            if (emailOutbox != null) {
                emailOutbox.setStatus("SENT");
                emailOutboxRepository.save(emailOutbox);
            }
        } catch (Exception e) {
            // 更新状态为失败
            EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(to, code, "PENDING");
            if (emailOutbox != null) {
                emailOutbox.setStatus("FAILED");
                emailOutbox.setRetryCount(emailOutbox.getRetryCount() + 1);
                emailOutboxRepository.save(emailOutbox);
            }
            throw new RuntimeException("邮件发送失败: " + e.getMessage());
        }
    }

    /**
     * 验证验证码
     */
    public boolean verifyCode(String email, String code) {
        EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(email, code, "SENT");
        
        if (emailOutbox == null) {
            return false;
        }
        
        // 检查是否过期
        if (emailOutbox.isExpired()) {
            emailOutbox.setStatus("EXPIRED");
            emailOutboxRepository.save(emailOutbox);
            return false;
        }
        
        // 验证成功，标记为已验证
        emailOutbox.setStatus("VERIFIED");
        emailOutboxRepository.save(emailOutbox);
        
        return true;
    }

    /**
     * 检查邮箱是否已验证
     */
    public boolean isEmailVerified(String email) {
        EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndStatus(email, "VERIFIED");
        return emailOutbox != null && !emailOutbox.isExpired();
    }

    /**
     * 发送密码重置验证码
     */
    public String sendPasswordResetCode(String email) {
        // 生成验证码
        String code = generateVerificationCode();
        
        // 保存到数据库
        EmailOutbox emailOutbox = new EmailOutbox(email, code, "PASSWORD_RESET");
        emailOutboxRepository.save(emailOutbox);
        
        // 发送邮件
        sendPasswordResetEmail(email, code);
        
        return code;
    }

    /**
     * 发送密码重置邮件
     */
    public void sendPasswordResetEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("密码重置验证码");
        
        String emailContent = String.format(
            "您好！\n\n" +
            "您请求重置密码的验证码是：%s\n\n" +
            "此验证码将在5分钟后过期，请及时使用。\n\n" +
            "如果您没有请求重置密码，请忽略此邮件。\n\n" +
            "谢谢！",
            code
        );
        
        message.setText(emailContent);
        
        try {
            mailSender.send(message);
            // 更新状态为已发送
            EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(to, code, "PENDING");
            if (emailOutbox != null) {
                emailOutbox.setStatus("SENT");
                emailOutboxRepository.save(emailOutbox);
            }
        } catch (Exception e) {
            // 更新状态为失败
            EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(to, code, "PENDING");
            if (emailOutbox != null) {
                emailOutbox.setStatus("FAILED");
                emailOutbox.setRetryCount(emailOutbox.getRetryCount() + 1);
                emailOutboxRepository.save(emailOutbox);
            }
            throw new RuntimeException("密码重置邮件发送失败: " + e.getMessage());
        }
    }

    /**
     * 验证密码重置验证码
     */
    public boolean verifyPasswordResetCode(String email, String code) {
        EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(email, code, "SENT");
        
        if (emailOutbox == null) {
            return false;
        }
        
        // 检查是否过期
        if (emailOutbox.isExpired()) {
            emailOutbox.setStatus("EXPIRED");
            emailOutboxRepository.save(emailOutbox);
            return false;
        }
        
        // 验证成功，标记为已验证
        emailOutbox.setStatus("VERIFIED");
        emailOutboxRepository.save(emailOutbox);
        
        return true;
    }
}