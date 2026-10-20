package comp5348.storeservice.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import comp5348.storeservice.adapter.BankAdapter;
import comp5348.storeservice.scheduler.OutboxProcessor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 失败场景表 #15：除了 OutboxProcessor，没有别的代码调 bank 扣款 / 退款。
 *
 * 【层】架构测试。不运行程序，ArchUnit 读编译好的 .class 文件，检查"谁调用了谁"。
 * 【为什么需要】规则 1、ADR-04：资金操作只有一个入口。入口只有一个，幂等键、重试、超时才只需要在一个地方做对。
 *   以后有人图省事在 Controller 里直接调 bankAdapter.transfer，这个测试马上变红。
 * 【不管的】开户、按邮箱查账户（createCustomerAccount、getAccountByOwnerEmail）不动钱，谁调都可以。
 */
class BankAccessArchitectureTest {

    // 只导入 store 的生产代码（不含测试代码）
    private final JavaClasses storeClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("comp5348.storeservice");

    /** "这次方法调用是在调 BankAdapter 的 transfer 或 refund" */
    private static final DescribedPredicate<JavaMethodCall> MOVES_MONEY =
            new DescribedPredicate<>("call BankAdapter.transfer/refund") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTargetOwner().isAssignableTo(BankAdapter.class)
                            && Set.of("transfer", "refund").contains(call.getName());
                }
            };

    @Test
    void onlyOutboxProcessorCallsBankTransferOrRefund() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(OutboxProcessor.class.getName())
                .should().callMethodWhere(MOVES_MONEY)
                .because("规则 1：全系统只有 OutboxProcessor 调 bank 扣款和退款")
                .check(storeClasses); // 违反时抛 AssertionError，列出每一处违规调用的 文件:行号
    }
}
