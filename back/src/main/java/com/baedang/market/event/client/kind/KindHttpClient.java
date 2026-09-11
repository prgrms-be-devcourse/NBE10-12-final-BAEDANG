package com.baedang.market.event.client.kind;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class KindHttpClient {

    private final RestClient restClient;

    public KindHttpClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    public String getText(URI uri, int maxBytes) {
        if (uri == null) {
            throw new IllegalArgumentException("uri는 필수입니다");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes는 0보다 커야 합니다");
        }

        try {
            return restClient.get()
                    .uri(uri)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new IllegalStateException("HTTP 요청 실패: status=" + status);
                        }
                        try (InputStream in = response.getBody()) {
                            byte[] buffer = in.readNBytes(maxBytes + 1);
                            if (buffer.length > maxBytes) {
                                throw new IllegalStateException("응답 크기 초과: 최대 " + maxBytes + " 바이트를 초과했습니다");
                            }
                            return new String(buffer, StandardCharsets.UTF_8);
                        }
                    });
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 요청 실패: " + e.getMessage(), e);
        }
    }
}
