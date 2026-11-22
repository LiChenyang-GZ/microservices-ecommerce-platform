package com.comp5348.delivery.events;

import com.comp5348.delivery.model.DeliveryDeadLetterAlert;
import com.comp5348.delivery.repository.DeliveryDeadLetterAlertRepository;
import com.comp5348.delivery.support.DeliveryIntegrationTest;
import com.comp5348.delivery.support.FakeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权 PR ③：delivery 的 DLQ 管理接口只给管理员。失败场景表 36 号 D5、A13、B6。
 *
 * 【层】集成测试：带 servlet 过滤器的 MockMvc（mvcWithFilters），用户 token 由假 store（FakeStore）验。
 * 【角色从哪来】store 的 validate-token 回复里的 role。没有 role 的老回复当成普通用户。
 * 【红灯为什么红】TokenAuthenticationFilter 只看 token 有没有效，不看角色：普通用户能列出、resolve、requeue 任何死信，
 *   **真的改了**（resolved 变 true）。
 */
class DlqAdminRoleIntegrationTest extends DeliveryIntegrationTest {

    @Autowired DeliveryDeadLetterAlertRepository alerts;

    @Test
    void normalUser_cannotListDeadLetters() throws Exception {
        mvcWithFilters().perform(get("/api/admin/dlq/delivery").header("Authorization", "Bearer " + FakeStore.USER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void normalUser_cannotResolveADeadLetter() throws Exception {
        DeliveryDeadLetterAlert alert = newAlert();

        mvcWithFilters().perform(post("/api/admin/dlq/delivery/{id}/resolve", alert.getId())
                        .header("Authorization", "Bearer " + FakeStore.USER_TOKEN))
                .andExpect(status().isForbidden());

        assertThat(alerts.findById(alert.getId()).orElseThrow().isResolved()).as("没被标成已处理").isFalse();
    }

    @Test
    void normalUser_cannotRequeueADeadLetter() throws Exception {
        DeliveryDeadLetterAlert alert = newAlert();

        mvcWithFilters().perform(post("/api/admin/dlq/delivery/{id}/requeue", alert.getId())
                        .header("Authorization", "Bearer " + FakeStore.USER_TOKEN))
                .andExpect(status().isForbidden());

        assertThat(alerts.findById(alert.getId()).orElseThrow().isResolved()).isFalse();
    }

    /** A13：store 的老回复没有 role → 普通用户 */
    @Test
    void replyWithoutARole_isAnOrdinaryUser() throws Exception {
        mvcWithFilters().perform(get("/api/admin/dlq/delivery").header("Authorization", "Bearer " + FakeStore.LEGACY_TOKEN))
                .andExpect(status().isForbidden());
    }

    /** 本来就绿：管理员能看、能处理 */
    @Test
    void admin_canListAndResolve() throws Exception {
        DeliveryDeadLetterAlert alert = newAlert();

        mvcWithFilters().perform(get("/api/admin/dlq/delivery").header("Authorization", "Bearer " + FakeStore.ADMIN_TOKEN))
                .andExpect(status().isOk());
        mvcWithFilters().perform(post("/api/admin/dlq/delivery/{id}/resolve", alert.getId())
                        .header("Authorization", "Bearer " + FakeStore.ADMIN_TOKEN))
                .andExpect(status().isOk());

        assertThat(alerts.findById(alert.getId()).orElseThrow().isResolved()).isTrue();
    }

    /** 本来就绿：没 token 还是 401 */
    @Test
    void withoutToken_isUnauthorized() throws Exception {
        mvcWithFilters().perform(get("/api/admin/dlq/delivery")).andExpect(status().isUnauthorized());
    }

    private DeliveryDeadLetterAlert newAlert() {
        return alerts.save(DeliveryDeadLetterAlert.builder()
                .deliveryId(999_999L)
                .severity("HIGH")
                .reason("test alert")
                .alertTimestamp(Instant.now())
                .resolved(false)
                .build());
    }
}
