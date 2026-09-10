package com.baedang.market.event.client.kind;

import com.baedang.market.event.entity.KrMarket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KindUriPolicyTest {

    private final KindUriPolicy policy = new KindUriPolicy(URI.create("https://kind.krx.co.kr"));

    @Test
    void rss_uri_is_strictly_formatted_for_kospi_and_kosdaq() {
        URI kospiUri = policy.rss(KrMarket.KOSPI);
        assertThat(kospiUri.toString()).isEqualTo(
                "https://kind.krx.co.kr/disclosure/rsstodaydistribute.do"
                        + "?method=searchRssTodayDistribute"
                        + "&repIsuSrtCd="
                        + "&mktTpCd=1"
                        + "&searchCorpName="
                        + "&currentPageSize=100");

        URI kosdaqUri = policy.rss(KrMarket.KOSDAQ);
        assertThat(kosdaqUri.toString()).isEqualTo(
                "https://kind.krx.co.kr/disclosure/rsstodaydistribute.do"
                        + "?method=searchRssTodayDistribute"
                        + "&repIsuSrtCd="
                        + "&mktTpCd=2"
                        + "&searchCorpName="
                        + "&currentPageSize=100");
    }

    @Test
    void viewer_uri_accepts_valid_14_digit_acpt_no() {
        URI uri = policy.viewer("20260713000658");
        assertThat(uri.toString()).isEqualTo(
                "https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658");

        URI parsed = policy.viewer(URI.create(
                "https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658"));
        assertThat(parsed).isEqualTo(uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123",
            "2026071300065", // 13 digits
            "202607130006589", // 15 digits
            "2026071300065a" // non-digit
    })
    void viewer_uri_rejects_invalid_acpt_no(String invalidAcptNo) {
        assertThatThrownBy(() -> policy.viewer(invalidAcptNo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void viewer_uri_rejects_foreign_host_or_unexpected_path_and_query() {
        assertThatThrownBy(() -> policy.viewer(URI.create(
                "https://evil.example/common/disclsviewer.do?method=search&acptNo=20260713000658")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.viewer(URI.create(
                "https://kind.krx.co.kr/other/path?method=search&acptNo=20260713000658")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.viewer(URI.create(
                "https://kind.krx.co.kr/common/disclsviewer.do?method=other&acptNo=20260713000658")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.viewer(URI.create(
                "http://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void external_detail_accepts_valid_pattern() {
        URI valid = URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");
        assertThat(policy.externalDetail(valid)).isEqualTo(valid);

        URI relative = URI.create("/external/2026/07/13/000273/20260713000658/99443.htm");
        assertThat(policy.externalDetail(relative)).isEqualTo(valid);
    }

    @Test
    void external_detail_rejects_other_hosts_and_unexpected_paths() {
        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://evil.example/external/2026/07/13/000273/20260713000658/99443.htm")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://kind.krx.co.kr/orders")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("http://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.jsp")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://kind.krx.co.kr/external/2026/07/13/abc/20260713000658/99443.htm")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://kind.krx.co.kr/external/../../etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://kind.krx.co.kr/external/%2e%2e/orders")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://kind.krx.co.kr:8443/external/2026/07/13/000273/20260713000658/99443.htm")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://user:pass@kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm?param=1")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy.externalDetail(
                URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm#frag")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
