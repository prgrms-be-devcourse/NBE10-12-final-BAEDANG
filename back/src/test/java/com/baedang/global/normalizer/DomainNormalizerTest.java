package com.baedang.global.normalizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class DomainNormalizerTest {

    @Test
    void 심볼과_통화는_앞뒤_공백과_대소문자만_정규화한다() {
        assertThat(DomainNormalizer.symbol(" \tbrk.b\n")).isEqualTo("BRK.B");
        assertThat(DomainNormalizer.symbol("005930")).isEqualTo("005930");
        assertThat(DomainNormalizer.symbol(" a b ")).isEqualTo("A B");
        assertThat(DomainNormalizer.currency(" usd ")).isEqualTo("USD");
        assertThat(DomainNormalizer.currency("unsupported")).isEqualTo("UNSUPPORTED");
    }

    @Test
    void 이메일과_검색어의_내부공백_정책은_서로_다르다() {
        assertThat(DomainNormalizer.email(" User@Example.COM ")).isEqualTo("user@example.com");
        assertThat(DomainNormalizer.email(" A B@Example.COM ")).isEqualTo("a b@example.com");
        assertThat(DomainNormalizer.searchKey(" \t삼 성\nElec \r")).isEqualTo("삼성elec");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void 누락값을_유효한_기본값으로_바꾸지_않는다(String raw) {
        String expected = raw == null ? null : "";
        assertThat(DomainNormalizer.symbol(raw)).isEqualTo(expected);
        assertThat(DomainNormalizer.currency(raw)).isEqualTo(expected);
        assertThat(DomainNormalizer.email(raw)).isEqualTo(expected);
        assertThat(DomainNormalizer.searchKey(raw)).isEqualTo(expected);
        assertThat(DomainNormalizer.upperCode(raw)).isEqualTo(expected);
        assertThat(DomainNormalizer.lowerCode(raw)).isEqualTo(expected);
    }

    @Test
    @ResourceLock("java.util.Locale.default")
    void 서버의_터키어_로케일에도_결과가_같다() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(DomainNormalizer.symbol("intc")).isEqualTo("INTC");
            assertThat(DomainNormalizer.currency("inr")).isEqualTo("INR");
            assertThat(DomainNormalizer.email("I@EXAMPLE.COM")).isEqualTo("i@example.com");
            assertThat(DomainNormalizer.searchKey(" I N T C ")).isEqualTo("intc");
            assertThat(DomainNormalizer.upperCode("individual")).isEqualTo("INDIVIDUAL");
            assertThat(DomainNormalizer.lowerCode("INDIVIDUAL")).isEqualTo("individual");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
