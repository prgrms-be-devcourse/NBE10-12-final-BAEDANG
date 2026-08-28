package com.baedang.standard.utils;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;

public final class Pacer {

    public static Pacer forTps(int tps) {
        if (tps <= 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "TPS는 1 이상이어야 함: " + tps);
        }
        return new Pacer((1000 + tps - 1) / tps);
    }

    private final long intervalMillis;
    private Pacer(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public void pace() {
        try {
            Thread.sleep(intervalMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Interruption during pace control");
        }
    }
}
