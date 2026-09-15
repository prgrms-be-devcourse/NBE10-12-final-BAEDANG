package com.baedang.account.support;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerCursorTest {

    @Test
    void 인코딩_후_디코딩하면_원래_entryId_가_나온다() {
        String cursor = LedgerCursor.encode(3040L);

        assertThat(LedgerCursor.decode(cursor)).isEqualTo(3040L);
    }

    @Test
    void api_명세의_커서_형식과_정확히_일치한다() {
        // docs/api-spec.md 의 nextCursor 예시 = Base64URL({"entryId":3040})
        assertThat(LedgerCursor.encode(3040L)).isEqualTo("eyJlbnRyeUlkIjozMDQwfQ");
    }

    @Test
    void 잘못된_Base64_는_INVALID_CURSOR_다() {
        assertThatThrownBy(() -> LedgerCursor.decode("!!not-base64!!"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURSOR));
    }

    @Test
    void entryId_필드가_없는_JSON_은_INVALID_CURSOR_다() {
        String noEntryId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"foo\":1}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> LedgerCursor.decode(noEntryId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURSOR));
    }
}
