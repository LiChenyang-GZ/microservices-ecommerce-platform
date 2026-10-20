package comp5348.storeservice.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import comp5348.storeservice.adapter.DeliveryAdapter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 失败场景表 B10（架构那一半）：store 不再用 HTTP 建配送单。
 *
 * 【层】架构测试（ArchUnit 读编译好的 .class，不运行程序）。
 * 【为什么需要】推进顺序第 5 步：建配送单只有一个入口——delivery 读 OrderPaid。
 *   store 再调 DeliveryAdapter.createDelivery 就是第二个入口：delivery 挂了会触发旧的"30 秒补偿退款"，
 *   而且 HTTP 建一张、OrderPaid 再建一张，靠 orderId 唯一约束才挡得住。
 * 【红灯为什么红】OutboxProcessor.processPaymentSuccess 还在调 deliveryAdapter.createDelivery。
 *   绿灯时这个方法连同调用一起删掉，规则自然满足。
 */
class DeliveryAccessArchitectureTest {

    private final JavaClasses storeClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("comp5348.storeservice");

    @Test
    void storeNeverCreatesDeliveryOverHttp() {
        noClasses()
                .should().callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "call DeliveryAdapter.createDelivery",
                        call -> call.getTargetOwner().isAssignableTo(DeliveryAdapter.class)
                                && call.getName().equals("createDelivery")))
                .because("推进顺序第 5 步：配送单只由 delivery 读 OrderPaid 来建")
                .check(storeClasses);
    }
}
