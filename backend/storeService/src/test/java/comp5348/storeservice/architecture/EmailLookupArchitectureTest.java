package comp5348.storeservice.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import comp5348.storeservice.repository.AccountRepository;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 74 号读的卡口（审查 75 号 🟡6）：只有 AccountRepository#findByEmail 自己能调精确匹配的 findByStoredEmail。
 *
 * 【层】架构测试，ArchUnit 读编译好的 .class，不运行程序。
 * 【为什么需要】findByStoredEmail 是 public 的，IDE 自动补全就在 findByEmail 旁边。以后有人直接调它，
 *   那条路径又区分大小写，其余 328 条测试照样全绿；这条马上变红。
 * 【现在就该绿】回归保护，不是红灯。
 */
class EmailLookupArchitectureTest {

    private final JavaClasses storeClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("comp5348.storeservice");

    private static final DescribedPredicate<JavaMethodCall> EXACT_EMAIL_LOOKUP =
            new DescribedPredicate<>("call AccountRepository.findByStoredEmail") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTargetOwner().isAssignableTo(AccountRepository.class)
                            && call.getName().equals("findByStoredEmail");
                }
            };

    @Test
    void onlyAccountRepositoryCallsTheExactEmailLookup() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(AccountRepository.class.getName())
                .should().callMethodWhere(EXACT_EMAIL_LOOKUP)
                .because("74 号：查邮箱要走 findByEmail，它先归一")
                .check(storeClasses);
    }
}
