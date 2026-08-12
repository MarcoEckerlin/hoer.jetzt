package eckerlin.dev.utils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.json.JSONObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.util.List;

import static eckerlin.dev.utils.Config.config;

public class HttpTool {

    protected static boolean isLoggedIn(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return false;

        Object user = session.getAttribute("user");
        Object token = session.getAttribute("token");
        Object uuid = session.getAttribute("uuid");

        return user != null && token != null && uuid != null;
    }

    protected static String getClientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    public static String createJsonResponse(int status, String message, String token) {
        JSONObject respond = new JSONObject();
        respond.put("status", status);
        respond.put("message", message);
        respond.put("token", token);
        return respond.toString();
    }

    public static String createJsonResponse(int status, String message) {
        JSONObject respond = new JSONObject();
        respond.put("status", status);
        respond.put("message", message);
        return respond.toString();
    }

    protected boolean ipAllowed(HttpServletRequest request) {
        String ipAddress = getClientIp(request);
        List<Object> allowed_ips = config.getJSONObject("api_settings").getJSONArray("ip_allowed").toList();

        if (allowed_ips.contains(ipAddress)) return true;
        if (allowed_ips.contains("0.0.0.0") || allowed_ips.contains("*")) return true;
        if (allowed_ips.contains(new String().contains("%"))) {
            for (int x = 0; x < allowed_ips.size(); x++) {
                Object entry = allowed_ips.get(x);
                if (entry == null) continue;
                String allowedEntry = entry.toString();
                if (allowedEntry.contains("%")) {
                    String startAddress = allowedEntry.split("%")[0];
                    if (ipAddress.startsWith(startAddress)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public String createJsonResponse(String status, String message) {
        JSONObject respond = new JSONObject();
        respond.put("status", status);
        respond.put("message", message);
        return respond.toString();
    }

    public static String noAccess() {
        JSONObject respond = new JSONObject();
        respond.put("status", 403);
        respond.put("access", "Please Login to your Account.");
        return respond.toString();
    }

    public String htmlEmailTemplate(String title, String text, String button_title, String button_url) {

        ClassPathResource resource = new ClassPathResource("static/mail-assets/email_template.html");
        String html;
        try (InputStream is = resource.getInputStream()) {
            byte[] bdata = FileCopyUtils.copyToByteArray(is);
            html = new String(bdata, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String personalizedMail = html
                .replace("{title}", title)
                .replace("{text}", text)
                .replace("{button_url}", button_url)
                .replace("{button_title}", button_title);

        return personalizedMail;
    }

    protected String resetCodeGenerator(String email, boolean createUser) {
        String sql = createUser
                ? "INSERT INTO api_auth_passwordReset (uuid, code, ts) " +
                "VALUES ((SELECT uuid FROM api_auth_acc WHERE email = ?), ?, DATE_ADD(NOW(), INTERVAL 715 MINUTE)) " +
                "ON DUPLICATE KEY UPDATE code = VALUES(code), ts = VALUES(ts);"
                : "INSERT INTO api_auth_passwordReset (uuid, code) " +
                "VALUES ((SELECT uuid FROM api_auth_acc WHERE email = ?), ?) " +
                "ON DUPLICATE KEY UPDATE code = VALUES(code);";

        final String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder codeBuilder = new StringBuilder(32);
        for (int i = 0; i < 32; i++) codeBuilder.append(abc.charAt(rnd.nextInt(abc.length())));
        String code = codeBuilder.toString();

        try (Connection connection = DB.connection();
             PreparedStatement checkStmt = connection.prepareStatement("SELECT uuid FROM api_auth_acc WHERE email = ?");
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            checkStmt.setString(1, email);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (!rs.next()) return HttpTool.createJsonResponse(404, "Email not found");
            }

            stmt.setString(1, email);
            stmt.setString(2, code);

            int rows = stmt.executeUpdate();
            if (rows <= 0) return null;

            return code;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}
