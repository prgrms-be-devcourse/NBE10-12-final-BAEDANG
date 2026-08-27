package com.baedang.account.controller;

import com.baedang.account.dto.AccountSummaryResponse;
import com.baedang.account.dto.HoldingsResponse;
import com.baedang.account.service.AccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계좌·마이페이지 API.
 *
 * <p>1주차에는 인증이 없어 {@code X-User-Id} 헤더로 사용자를 식별하고,
 * 헤더가 없으면 {@code auth.dev-fixed-user-id}(시드 사용자)로 폴백합니다.
 * 2주차에 JWT 를 붙이면 헤더 처리 대신 인증 주체에서 userId 를 꺼내세요.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final Long fallbackUserId;

    public AccountController(AccountService accountService,
                            @Value("${auth.dev-fixed-user-id}") Long fallbackUserId) {
        this.accountService = accountService;
        this.fallbackUserId = fallbackUserId;
    }

    @GetMapping("/me")
    public ResponseEntity<AccountSummaryResponse> getSummary(
            @RequestHeader(value = "X-User-Id", required = false) Long userId
    ) {
        return ResponseEntity.ok(accountService.getSummary(resolveUserId(userId)));
    }

    @GetMapping("/me/holdings")
    public ResponseEntity<HoldingsResponse> getHoldings(
            @RequestHeader(value = "X-User-Id", required = false) Long userId
    ) {
        return ResponseEntity.ok(accountService.getHoldings(resolveUserId(userId)));
    }

    private Long resolveUserId(Long userId) {
        return userId != null ? userId : fallbackUserId;
    }
}
