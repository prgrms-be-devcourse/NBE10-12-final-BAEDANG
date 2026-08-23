package com.baedang.user.entity;

/** 탈퇴를 물리 삭제로 처리하면 원장의 FK 가 깨지므로 상태 전환으로만 다룹니다. */
public enum UserStatus { ACTIVE, DORMANT, WITHDRAWN }
