package com.baedang.trading.entity;

/**
 * 원장 항목. <b>세 가지뿐입니다.</b>
 *
 * <p>수수료·세금은 별도 항목으로 쪼개지 않고 {@link #BUY}/{@link #SELL} 금액에
 * 포함합니다. 원장 한 줄이 {@code trade_order.net_amount} 하나에 대응하므로
 * 목록이 절반으로 짧아지고 커서 처리도 단순해집니다.
 * 수수료 총액이 필요하면 {@code SUM(trade_order.fee)} 로 구하면 됩니다.
 *
 * <p>포트폴리오 초기화용 RESET 항목은 두지 않습니다 — 초기화는 새 계좌를
 * 만드는 일이고, 새 계좌의 {@link #INITIAL_DEPOSIT} 한 줄이 그 역할을 합니다.
 */
public enum EntryType { INITIAL_DEPOSIT, BUY, SELL }
