package comp5348.storeservice.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * JWT User Helper class for getting current logged-in user information from SecurityContext
 */
@Component
public class JwtUserHelper {

    /**
     * Get current logged-in user's email
     * @return User email, returns null if not logged in
     */
    public String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName(); // In JWT, name is email
        }
        return null;
    }

    /**
     * Check if there is currently a logged-in user
     * @return true if user is logged in, false otherwise
     */
    public boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated();
    }
}
