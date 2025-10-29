package comp5348.storeservice.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * JWT 用户辅助类，用于从 SecurityContext 获取当前登录用户信息
 */
@Component
public class JwtUserHelper {

    /**
     * 获取当前登录用户的邮箱（email）
     * @return 用户邮箱，如果未登录返回 null
     */
    public String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName(); // 在 JWT 中，name 就是 email
        }
        return null;
    }

    /**
     * 检查当前是否有用户登录
     * @return true 如果有用户登录，false 否则
     */
    public boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated();
    }
}
