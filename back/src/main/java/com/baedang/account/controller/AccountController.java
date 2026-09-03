package com.baedang.account.controller;

import com.baedang.account.dto.AccountResetRequest;
import com.baedang.account.dto.AccountResetResponse;
import com.baedang.account.dto.AccountSummaryResponse;
import com.baedang.account.dto.HoldingsResponse;
import com.baedang.account.dto.LedgerResponse;
import com.baedang.account.service.AccountResetService;
import com.baedang.account.service.AccountService;
import com.baedang.account.service.LedgerQueryService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 계좌·마이페이지 API. 인증된 JWT subject의 userId만 신뢰합니다. */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AccountResetService accountResetService;
    private final LedgerQueryService ledgerQueryService;

    public AccountController(AccountService accountService,
                             AccountResetService accountResetService,
                             LedgerQueryService ledgerQueryService) {
        this.accountService = accountService;
        this.accountResetService = accountResetService;
        this.ledgerQueryService = ledgerQueryService;
    }

    @GetMapping("/me")
    public ResponseEntity<AccountSummaryResponse> getSummary(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(accountService.getSummary(userId));
    }

    @GetMapping("/me/holdings")
    public ResponseEntity<HoldingsResponse> getHoldings(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(accountService.getHoldings(userId));
    }

    @PostMapping("/me/reset")
    public ResponseEntity<AccountResetResponse> reset(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody AccountResetRequest request
    ) {
        return ResponseEntity.ok(accountResetService.reset(userId, request.accountId()));
    }

    @GetMapping("/me/ledger")
    public ResponseEntity<LedgerResponse> getLedger(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String entryType
    ) {
        return ResponseEntity.ok(
                ledgerQueryService.getLedger(userId, cursor, size, entryType));
    }

}
