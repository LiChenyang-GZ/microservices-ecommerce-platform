package comp5348.storeservice.auth;

import comp5348.storeservice.support.StoreHttpTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 鉴权 PR ②、③：store 每个路径一行，"没 token / 普通用户 / 管理员"三种人各得到什么。PR ② 独立审查 🟡7：
 * 以前只测了几条，有人把 SecurityConfig 的 matcher 改松（比如写回 /api/products/**）测试报不出来。
 *
 * 【只看安全链的结论】401 = 安全链说没登录；403 = 安全链说角色不够；"open" = 安全链放行了
 *   （之后 controller 回什么都行：200、400、404，只要不是 401 / 403）。写操作也安全：没 token 和角色不够的请求到不了 controller；
 *   放行的那几条只用不存在的 id 或空请求体，什么都改不了。controller 没接住的异常（MockMvc 不走错误页，会直接抛）也算放行。
 * 【不在表里的】登录、validate-token：请求体不对时它们自己回 401，从状态码分不出是谁拦的；它们公开与否由
 *   StoreAuthorizationIntegrationTest、AdminRoleIntegrationTest 里真的登录、真的验 token 的测试证明。
 * 【红灯为什么红】还没有角色：管理员接口对普通用户是 open，不是 403。没 token 的那一列本来就对（PR ② 做的）。
 */
class PathAccessRegressionTest extends StoreHttpTest {

    @ParameterizedTest(name = "{0} {1} → none {2}, user {3}, admin {4}")
    @CsvSource({
            // 公开
            "POST, /api/user,                                        open, open, open",
            "POST, /api/user/activate,                               open, open, open",
            "POST, /api/user/forgot-password,                        open, open, open",
            "POST, /api/user/reset-password,                         open, open, open",
            "GET,  /api/products,                                    open, open, open",
            "GET,  /api/products/available,                          open, open, open",
            "GET,  /api/products/999999999,                          open, open, open",
            "GET,  /api/products/search?name=x,                      open, open, open",
            "GET,  /api/products/search/price?minPrice=1&maxPrice=2, open, open, open",
            "GET,  /actuator/health,                                 open, open, open",
            "GET,  /actuator/health/liveness,                        open, open, open",
            "GET,  /actuator/health/readiness,                       open, open, open",
            // 登录用户（订单自己的；归属在 service 层查，另有测试）
            "POST, /api/store/orders/create-with-payment,            401,  open, open",
            "GET,  /api/store/orders/999999999/with-payment,         401,  open, open",
            // 别人的列表：403 是 service 层的归属检查（OrderProductService）回的，不是安全链；管理员也不例外
            "GET,  /api/store/orders/user/999999999/with-payment,    401,  403,  403",
            "POST, /api/store/orders/999999999/cancel,               401,  open, open",
            "POST, /api/store/orders/999999999/retry-payment,        401,  open, open",
            "GET,  /api/store/products/999999999/stock,              401,  open, open",
            // 管理员
            "POST, /api/payments/999999999/refund,                   401,  403,  open",
            "GET,  /api/orders,                                      401,  403,  open",
            "GET,  /api/warehouses,                                  401,  403,  open",
            "GET,  /api/warehouses/999999999,                        401,  403,  open",
            "POST, /api/warehouses,                                  401,  403,  open",
            "PUT,  /api/warehouses/999999999,                        401,  403,  open",
            "DELETE, /api/warehouses/999999999,                      401,  403,  open",
            "PUT,  /api/warehouses/products/quantity,                401,  403,  open",
            "GET,  /api/warehouses/audit-logs/out-transactions,      401,  403,  open",
            "GET,  /api/warehouses/audit-logs/failed,                401,  403,  open",
            "GET,  /api/warehouses/audit-logs/product/999999999,     401,  403,  open",
            "GET,  /api/warehouses/audit-logs/warehouse/999999999,   401,  403,  open",
            "GET,  /api/warehouses/admin/out-transactions,           401,  403,  open",
            "GET,  /api/warehouses/admin/out-transactions/product/999999999,   401, 403, open",
            "GET,  /api/warehouses/admin/out-transactions/warehouse/999999999, 401, 403, open",
            "GET,  /api/warehouses/999999999/inventory-records,      401,  403,  open",
            "POST, /api/products,                                    401,  403,  open",
            "PUT,  /api/products/999999999,                          401,  403,  open",
            "POST, /api/products/999999999/assign,                   401,  403,  open",
            "GET,  /api/products/999999999/inventory-records,        401,  403,  open",
            // 47 号 🟡3：HEAD 交给 GET 的处理器（真的查了全表），规则只写了 GET 就漏了它
            "HEAD, /api/orders,                                      401,  403,  open",
            "HEAD, /api/products/999999999/inventory-records,        401,  403,  open",
    })
    void securityChainDecision(String method, String path, String none, String user, String admin) throws Exception {
        assertThat(decision(method, path, null)).as("没 token").isEqualTo(none);
        assertThat(decision(method, path, bearer(newUser()))).as("普通用户").isEqualTo(user);
        assertThat(decision(method, path, adminBearer(newUser()))).as("管理员").isEqualTo(admin);
    }

    /** 401 / 403 原样返回，其他状态码都算安全链放行了（"open"） */
    private String decision(String method, String path, String authorization) throws Exception {
        var request = request(HttpMethod.valueOf(method), path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        int code;
        try {
            code = mvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception thrownByController) {
            return "open"; // 到了 controller 才抛：安全链放行了
        }
        return code == 401 || code == 403 ? String.valueOf(code) : "open";
    }
}
