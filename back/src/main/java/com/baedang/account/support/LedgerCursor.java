package com.baedang.account.support;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 체결 내역(원장) 페이지네이션 커서.
 *
 * <p>커서는 <b>불투명(opaque)</b>합니다 — 클라이언트는 내부 구조를 몰라도 되고,
 * 이전 응답의 {@code nextCursor} 를 그대로 다음 요청에 실어 보내기만 하면 됩니다.
 * 값은 {@code Base64URL(JSON {"entryId":N})} 이고, {@code entry_id} 하나만 담습니다.
 * {@code entry_id} 는 단조 증가 PK 라 정렬·비교가 안정적이어서 이 하나로 충분합니다.
 *
 * <p>디코딩이 실패하면(잘못된 Base64/JSON/필드 누락) 서버 버그가 아니라 잘못된 요청이므로
 * {@link ErrorCode#INVALID_CURSOR}(400) 로 끊습니다 — "처음부터 다시 불러오세요".
 */
public final class LedgerCursor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENTRY_ID = "entryId";

    private LedgerCursor() {
    }

    public static String encode(Long entryId) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(Map.of(ENTRY_ID, entryId));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            // Map.of(String, Long) 직렬화는 실패할 수 없다. 방어적으로만 감싼다.
            throw new IllegalStateException("커서 인코딩에 실패했습니다", e);
        }
    }

    public static Long decode(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.UTF_8));
            JsonNode node = MAPPER.readTree(json).get(ENTRY_ID);
            if (node == null || !node.canConvertToLong()) {
                throw new BusinessException(ErrorCode.INVALID_CURSOR);
            }
            return node.asLong();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR, "커서 디코딩 실패: " + cursor);
        }
    }
}
