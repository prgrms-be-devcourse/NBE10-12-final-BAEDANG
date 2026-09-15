package com.baedang.auth.dto;

import com.baedang.user.entity.Account;
import com.baedang.user.entity.User;
import java.time.Instant;

public record AuthResponse(
        Long userId,
        String email,
        String nickname,
        String accessToken,
        String refreshToken,
        AccountInfo account,
        Instant expiresAt
) {
    public record AccountInfo(
            Long accountId,
            Integer roundNo,
            String initialCash,
            String cashBalance
    ){
    }

    public static AuthResponse from(
            User user,
            Account account,
            String accessToken,
            String refreshToken,
            Instant expiresAt
    ){
        AccountInfo accountInfo = new AccountInfo(
                account.getAccountId(),
                account.getRoundNo(),
                account.getInitialCash().toPlainString(),
                account.getCashBalance().toPlainString()
        );
        return new AuthResponse(
                user.getUserId(),
                user.getEmail(),
                user.getNickname(),
                accessToken,
                refreshToken,
                accountInfo,
                expiresAt
        );
    }
}
