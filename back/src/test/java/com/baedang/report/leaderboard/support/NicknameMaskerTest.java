package com.baedang.report.leaderboard.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NicknameMaskerTest {

    @Test
    void 세글자_이상은_가운데를_전부_가린다() {
        assertThat(NicknameMasker.mask("홍길동")).isEqualTo("홍*동");
        assertThat(NicknameMasker.mask("시드투자자")).isEqualTo("시***자");
        assertThat(NicknameMasker.mask("abcd")).isEqualTo("a**d");
    }

    @Test
    void 두글자는_가운데가_없어_끝글자를_가린다() {
        assertThat(NicknameMasker.mask("홍길")).isEqualTo("홍*");
    }

    @Test
    void 한글자와_빈값은_별표로만_노출한다() {
        assertThat(NicknameMasker.mask("홍")).isEqualTo("*");
        assertThat(NicknameMasker.mask("")).isEqualTo("*");
        assertThat(NicknameMasker.mask(null)).isEqualTo("*");
    }

    @Test
    void 보조평면_문자도_코드포인트로_센다() {
        // 이모지 3개(각 보조평면) → 첫·끝 노출, 가운데 하나 마스킹.
        String emojis = "😀😁😂";
        assertThat(NicknameMasker.mask(emojis)).isEqualTo("😀*😂");
    }
}
