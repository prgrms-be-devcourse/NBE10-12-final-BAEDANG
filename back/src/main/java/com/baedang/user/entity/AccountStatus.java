package com.baedang.user.entity;

/**
 * 회차 계좌의 상태.
 * 부분 유니크 인덱스로 회원당 ACTIVE 계좌가 하나만 존재하도록 DB 가 강제합니다.
 */
public enum AccountStatus { ACTIVE, CLOSED }
