package com.comp5348.delivery.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 冒充 store 的 POST /api/user/validate-token（delivery 的 TokenValidationClient 调它）。用 JDK 自带的 HttpServer，不加依赖。
 *
 * 认三个固定的 token，其他一律当成无效（401，和 store 一样）：
 *   USER_TOKEN   → 普通用户，role = USER
 *   ADMIN_TOKEN  → 管理员，role = ADMIN
 *   LEGACY_TOKEN → 角色上线之前的回复：没有 role 字段（36 号 A13：要当成普通用户）
 */
public final class FakeStore {

    public static final String USER_TOKEN = "user-token";
    public static final String ADMIN_TOKEN = "admin-token";
    public static final String LEGACY_TOKEN = "legacy-token";
    public static final String USER_EMAIL = "user@test.local";
    public static final String ADMIN_EMAIL = "admin@test.local";

    private static HttpServer server;

    private FakeStore() {}

    /** 启动一次，整个测试进程共用；返回 store 的地址（给 store.service.url） */
    public static synchronized String start() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("cannot start fake store", e);
            }
            server.createContext("/api/user/validate-token", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int status = 200;
                String reply;
                if (body.contains("\"" + USER_TOKEN + "\"")) {
                    reply = "{\"valid\":true,\"userId\":1,\"email\":\"" + USER_EMAIL + "\",\"role\":\"USER\",\"message\":\"ok\"}";
                } else if (body.contains("\"" + ADMIN_TOKEN + "\"")) {
                    reply = "{\"valid\":true,\"userId\":2,\"email\":\"" + ADMIN_EMAIL + "\",\"role\":\"ADMIN\",\"message\":\"ok\"}";
                } else if (body.contains("\"" + LEGACY_TOKEN + "\"")) {
                    reply = "{\"valid\":true,\"userId\":3,\"email\":\"legacy@test.local\",\"message\":\"ok\"}";
                } else {
                    status = 401;
                    reply = "{\"valid\":false,\"message\":\"Token is invalid or expired\"}";
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }
        return "http://localhost:" + server.getAddress().getPort();
    }
}
