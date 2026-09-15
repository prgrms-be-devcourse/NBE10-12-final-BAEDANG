package com.baedang.report.support;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "CKSA", "CKSB", "CKEA", "CKEB", "CGSA", "CGSB", "CGEA", "CGEB",
            "DKSA", "DKSB", "DKEA", "DKEB", "DGSA", "DGSB", "DGEA", "DGEB"
    })
    void 저장된_16개_유형_코드를_복원한다(String code) {
        assertThat(InvestmentType.fromCode(code).code()).isEqualTo(code);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"CKS", "CKSBB", "XKSB", "CXSB", "CKXB", "CKSX", "cksb"})
    void 잘못된_코드는_공통_비즈니스_예외로_거절한다(String code) {
        assertThatThrownBy(() -> InvestmentType.fromCode(code))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(exception.getDetail()).contains(String.valueOf(code));
                    assertThat(exception.getData()).isNull();
                });
    }
}
