package com.baedang.stock.scheduler;

import com.baedang.stock.service.MinuteCandleCollectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MinuteCandleCollectionSchedulerTest {

    @Mock MinuteCandleCollectionService collectionService;

    @Test
    @DisplayName("매분 트리거되면 수집 서비스에 위임한다")
    void t1_위임() {
        MinuteCandleCollectionScheduler scheduler = new MinuteCandleCollectionScheduler(collectionService);

        scheduler.collect();

        verify(collectionService).collectOpenMarkets();
    }
}
