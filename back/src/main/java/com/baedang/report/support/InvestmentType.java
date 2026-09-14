package com.baedang.report.support;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;

/**
 * 투자 성향 MBTI — 4개 이진축의 조합(16유형).
 *
 * <p>각 축은 투자 "스타일"만 나타낸다. 성과(수익률)는 유형이 아니라 리포트의 성과 섹션과
 * 리더보드로 분리한다. 축 값은 보유 종목을 <b>평가금액 가중</b>으로 집계해 컷오프로 가른다
 * ({@link InvestmentTypeClassifier}).
 *
 * <p>코드는 네 글자(예: {@code CKSA} = 집중·국내·개별주·공격형).
 */
public record InvestmentType(
        Diversification diversification,
        Market market,
        Instrument instrument,
        Risk risk
) {

    /** 분산도: 한 종목에 몰렸나(집중) vs 여러 종목에 퍼졌나(분산). */
    public enum Diversification {
        CONCENTRATED("집중", 'C'),
        DIVERSIFIED("분산", 'D');
        public final String label;
        public final char letter;
        Diversification(String label, char letter) { this.label = label; this.letter = letter; }
    }

    /** 시장 선호: 국내(KR) vs 해외(US). */
    public enum Market {
        DOMESTIC("국내", 'K'),
        GLOBAL("해외", 'G');
        public final String label;
        public final char letter;
        Market(String label, char letter) { this.label = label; this.letter = letter; }
    }

    /** 종목 유형: 개별주(개별주·우선주) vs 펀드(ETF·ETN). */
    public enum Instrument {
        INDIVIDUAL("개별주", 'S'),
        FUND("ETF", 'E');
        public final String label;
        public final char letter;
        Instrument(String label, char letter) { this.label = label; this.letter = letter; }
    }

    /** 공격성: 레버리지·인버스 비중이 높으면 공격, 아니면 안정. (Phase 1은 변동성 미반영 프록시.) */
    public enum Risk {
        AGGRESSIVE("공격", 'A'),
        STABLE("안정", 'B');
        public final String label;
        public final char letter;
        Risk(String label, char letter) { this.label = label; this.letter = letter; }
    }

    /** 네 글자 유형 코드(예: {@code CKSA}). */
    public String code() {
        return "" + diversification.letter + market.letter + instrument.letter + risk.letter;
    }

    /** 사람이 읽는 유형명(예: "집중·국내·개별주·공격형"). */
    public String label() {
        return diversification.label + "·" + market.label + "·" + instrument.label + "·" + risk.label + "형";
    }

    /** 저장된 유형 코드(예: {@code CKSB})를 유형으로 복원한다. 리더보드 유형 비교에서 라벨을 얻을 때 쓴다. */
    public static InvestmentType fromCode(String code) {
        if (code == null || code.length() != 4) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "유형 코드는 4자여야 합니다: " + code);
        }
        Diversification d = null;
        for (Diversification v : Diversification.values()) {
            if (v.letter == code.charAt(0)) { d = v; }
        }
        Market m = null;
        for (Market v : Market.values()) {
            if (v.letter == code.charAt(1)) { m = v; }
        }
        Instrument i = null;
        for (Instrument v : Instrument.values()) {
            if (v.letter == code.charAt(2)) { i = v; }
        }
        Risk r = null;
        for (Risk v : Risk.values()) {
            if (v.letter == code.charAt(3)) { r = v; }
        }
        if (d == null || m == null || i == null || r == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 유형 코드: " + code);
        }
        return new InvestmentType(d, m, i, r);
    }
}
