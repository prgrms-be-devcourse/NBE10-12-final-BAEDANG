package com.baedang.report.leaderboard.support;

/**
 * 리더보드 노출용 닉네임 마스킹(설계문서 §6.5). 가운데 글자를 {@code *}로 가려 개인을 특정하기
 * 어렵게 하되 순위표의 사람 냄새는 남긴다. 첫·끝 글자는 노출한다.
 *
 * <ul>
 *   <li>1글자: {@code *}
 *   <li>2글자: 첫 글자 + {@code *} (가운데가 없어 끝 글자를 가린다)
 *   <li>3글자 이상: 첫 글자 + 가운데 전부 {@code *} + 끝 글자 ("홍길동" → "홍*동")
 * </ul>
 * 코드 포인트 단위로 처리해 이모지·보조평면 문자도 안전하게 센다.
 */
public final class NicknameMasker {

    private NicknameMasker() {
    }

    public static String mask(String nickname) {
        if (nickname == null || nickname.isEmpty()) {
            return "*";
        }
        int[] cps = nickname.codePoints().toArray();
        int len = cps.length;
        if (len == 1) {
            return "*";
        }
        if (len == 2) {
            return new String(cps, 0, 1) + "*";
        }
        return new String(cps, 0, 1) + "*".repeat(len - 2) + new String(cps, len - 1, 1);
    }
}
