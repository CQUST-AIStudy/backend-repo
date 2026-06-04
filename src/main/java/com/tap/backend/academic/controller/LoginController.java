package com.tap.backend.academic.controller;

import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoginController.class);

    @Autowired
    private UserService userService;

    @Autowired
    private LegacySessionAccessResolver legacySessionAccessResolver;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @GetMapping("/api/user/{username}")
    public ResponseEntity<Map<String, Object>> findUserByUsername(
            @PathVariable String username,
            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String authorizedUsername = legacySessionAccessResolver.requireUsernameReadAccess(username, request);
            UserEntity user = userService.findByUsername(authorizedUsername);
            if (user == null) {
                response.put("success", false);
                response.put("message", "user not found");
                return ResponseEntity.ok(response);
            }
            response.put("success", true);
            response.put("user", toUserInfo(user));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "load user failed: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody UserEntity loginUser,
            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            UserEntity user = userService.findByUsername(loginUser.getUsername());
            if (user == null) {
                response.put("success", false);
                response.put("message", "user not found");
                return ResponseEntity.ok(response);
            }

            if (!passwordMatches(loginUser.getPassword(), user.getPassword())) {
                response.put("success", false);
                response.put("message", "invalid password");
                return ResponseEntity.ok(response);
            }

            String roleMismatchMessage = resolveRoleMismatchMessage(user.getRole(), loginUser.getRole());
            if (roleMismatchMessage != null) {
                response.put("success", false);
                response.put("message", roleMismatchMessage);
                return ResponseEntity.ok(response);
            }

            upgradePasswordIfNeeded(user, loginUser.getPassword());

            HttpSession session = request.getSession(true);
            session.setAttribute("currentUser", user);
            session.setAttribute("username", user.getUsername());
            session.setAttribute("userId", user.getId());
            session.setAttribute("userRole", user.getRole());

            response.put("success", true);
            response.put("message", "login success");
            response.put("user", toUserInfo(user));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "login failed: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping("/api/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> regData) {
        Map<String, Object> response = new HashMap<>();
        try {
            String username = regData.get("username");
            String password = regData.get("password");
            String usernum = regData.get("usernum");
            String classname = regData.get("classname");

            if (isBlank(username) || isBlank(password)) {
                response.put("success", false);
                response.put("message", "username and password are required");
                return ResponseEntity.ok(response);
            }
            if (isBlank(usernum) || isBlank(classname)) {
                response.put("success", false);
                response.put("message", "student usernum and classname are required");
                return ResponseEntity.ok(response);
            }

            UserEntity existingUser = userService.findByUsername(username);
            if (existingUser != null) {
                response.put("success", false);
                response.put("message", "username already exists");
                return ResponseEntity.ok(response);
            }

            UserEntity user = new UserEntity();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password));
            user.setRole("student");
            user.setUsernum(usernum);
            user.setClassname(classname);

            boolean saved = userService.saveUser(user);
            if (!saved) {
                response.put("success", false);
                response.put("message", "register failed");
                return ResponseEntity.ok(response);
            }

            try {
                userService.bindStudentByUsernum(username, usernum, classname);
            } catch (Exception e) {
                log.warn("student binding warning: {}", e.getMessage());
            }

            response.put("success", true);
            response.put("message", "register success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "register failed: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping({"/logout", "/api/logout"})
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        response.put("success", true);
        response.put("message", "logout success");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/user/password")
    public ResponseEntity<Map<String, Object>> updatePassword(
            @RequestBody Map<String, String> passwordData,
            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            UserEntity currentUser = legacySessionAccessResolver.requireAuthenticated(request);
            String username = currentUser.getUsername();
            if (isBlank(username)) {
                response.put("success", false);
                response.put("message", "authentication required");
                return ResponseEntity.ok(response);
            }

            String oldPassword = passwordData.get("oldPassword");
            String newPassword = passwordData.get("newPassword");
            if (oldPassword == null || newPassword == null) {
                response.put("success", false);
                response.put("message", "oldPassword and newPassword are required");
                return ResponseEntity.ok(response);
            }

            UserEntity user = userService.findByUsername(username);
            if (user == null) {
                response.put("success", false);
                response.put("message", "user not found");
                return ResponseEntity.ok(response);
            }

            if (!passwordMatches(oldPassword, user.getPassword())) {
                response.put("success", false);
                response.put("message", "invalid current password");
                return ResponseEntity.ok(response);
            }

            user.setPassword(passwordEncoder.encode(newPassword));
            boolean updated = userService.updateUser(user);
            if (updated) {
                response.put("success", true);
                response.put("message", "password updated");
            } else {
                response.put("success", false);
                response.put("message", "password update failed");
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "password update failed: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String resolveRoleMismatchMessage(String actualRole, String requestedRole) {
        String expectedRole = normalizeRole(requestedRole);
        if (expectedRole == null) {
            return null;
        }
        String normalizedActualRole = normalizeRole(actualRole);
        if (expectedRole.equals(normalizedActualRole)) {
            return null;
        }
        return switch (expectedRole) {
            case "admin" -> "该账号不是管理员账号，请切换正确身份登录";
            case "teacher" -> "该账号不是教师账号，请切换正确身份登录";
            case "student" -> "该账号不是学生账号，请切换正确身份登录";
            default -> "不支持的登录身份：" + requestedRole;
        };
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case "admin", "administrator" -> "admin";
            case "teacher" -> "teacher";
            case "student" -> "student";
            default -> role.trim().toLowerCase(Locale.ROOT);
        };
    }

    private boolean passwordMatches(String rawPassword, String storedPassword) {
        if (isBlank(rawPassword) || isBlank(storedPassword)) {
            return false;
        }
        if (isEncodedPassword(storedPassword)) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        return rawPassword.equals(storedPassword);
    }

    private void upgradePasswordIfNeeded(UserEntity user, String rawPassword) {
        if (user == null || isBlank(rawPassword) || isEncodedPassword(user.getPassword())) {
            return;
        }
        user.setPassword(passwordEncoder.encode(rawPassword));
        userService.updateUser(user);
    }

    private boolean isEncodedPassword(String password) {
        return password != null && password.startsWith("$2");
    }

    private Map<String, Object> toUserInfo(UserEntity user) {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("role", user.getRole());
        userInfo.put("email", user.getEmail());
        userInfo.put("usernum", user.getUsernum());
        userInfo.put("class", user.getClassname());
        return userInfo;
    }
}
