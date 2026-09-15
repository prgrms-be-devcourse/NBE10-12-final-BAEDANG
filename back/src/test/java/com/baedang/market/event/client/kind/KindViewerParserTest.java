package com.baedang.market.event.client.kind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KindViewerParserTest {

    private KindViewerParser parser;

    @BeforeEach
    void setUp() {
        KindUriPolicy uriPolicy = new KindUriPolicy(URI.create("https://kind.krx.co.kr"));
        parser = new KindViewerParser(uriPolicy);
    }

    @Test
    void viewer_resolves_only_the_whitelisted_external_document() {
        URI viewerUri = URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=searchInitInfo&acptNo=20260713000658&docno=1");
        URI result = parser.externalDetailUri(viewerUri, fixture("viewer-cb.html"));

        assertThat(result).isEqualTo(URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm"));
    }

    @Test
    void rejects_external_document_from_untrusted_viewer_uri() {
        URI viewerUri = URI.create(
                "https://evil.example/common/disclsviewer.do?method=search&acptNo=20260713000658");

        assertThatThrownBy(() -> parser.externalDetailUri(viewerUri, fixture("viewer-cb.html")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_two_external_links() {
        String html = """
                <html><body>
                  <iframe src="/external/2026/07/13/000273/20260713000658/99443.htm"></iframe>
                  <a href="/external/2026/07/13/000273/20260713000658/99444.htm">link</a>
                </body></html>
                """;
        URI viewerUri = URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=searchInitInfo&acptNo=20260713000658");
        assertThatThrownBy(() -> parser.externalDetailUri(viewerUri, html))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_external_link_with_different_acpt_no() {
        String html = """
                <html><body>
                  <iframe src="/external/2026/07/13/000273/20260713000999/99443.htm"></iframe>
                </body></html>
                """;
        URI viewerUri = URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=searchInitInfo&acptNo=20260713000658");
        assertThatThrownBy(() -> parser.externalDetailUri(viewerUri, html))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_html_without_external_links() {
        String html = "<html><body><div>No links</div></body></html>";
        URI viewerUri = URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=searchInitInfo&acptNo=20260713000658");
        assertThatThrownBy(() -> parser.externalDetailUri(viewerUri, html))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String fixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/kind/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
