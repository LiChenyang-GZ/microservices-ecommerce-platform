package comp5348.storeservice;

import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;

// 冒烟测试：整个 Spring 容器能启动起来。
// 继承 StoreIntegrationTest：用 Testcontainers 的一次性 Postgres，不再连本机的 webuser 库。
class StoreServiceApplicationTests extends StoreIntegrationTest {

	@Test
	void contextLoads() {
	}

}
