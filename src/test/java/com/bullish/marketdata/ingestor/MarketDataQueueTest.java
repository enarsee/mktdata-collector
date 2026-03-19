package com.bullish.marketdata.ingestor;

import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.DataType;
import com.bullish.marketdata.model.Exchange;
import com.bullish.marketdata.model.MarketData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataQueueTest {

    private Candlestick testCandle() {
        return new Candlestick(
            Exchange.BINANCE, "BTCUSDT",
            Instant.parse("2026-03-19T00:00:00Z"),
            Instant.parse("2026-03-19T00:01:00Z"),
            new BigDecimal("67000"), new BigDecimal("67100"),
            new BigDecimal("66900"), new BigDecimal("67050"),
            new BigDecimal("100")
        );
    }

    @Test
    void shouldFanOutToMultipleConsumers() throws InterruptedException {
        MarketDataQueue queue = new MarketDataQueue();

        List<MarketData> consumer1Received = new ArrayList<>();
        List<MarketData> consumer2Received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        queue.subscribe(DataType.KLINE, data -> { consumer1Received.add(data); latch.countDown(); });
        queue.subscribe(DataType.KLINE, data -> { consumer2Received.add(data); latch.countDown(); });

        queue.start();
        queue.publish(testCandle());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, consumer1Received.size());
        assertEquals(1, consumer2Received.size());

        queue.stop();
    }

    @Test
    void shouldDeliverMultipleMessages() throws InterruptedException {
        MarketDataQueue queue = new MarketDataQueue();

        List<MarketData> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        queue.subscribe(DataType.KLINE, data -> { received.add(data); latch.countDown(); });

        queue.start();
        queue.publish(testCandle());
        queue.publish(testCandle());
        queue.publish(testCandle());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(3, received.size());

        queue.stop();
    }

    @Test
    void shouldFilterByDataType() throws InterruptedException {
        MarketDataQueue queue = new MarketDataQueue();

        List<MarketData> klineReceived = new ArrayList<>();
        List<MarketData> orderbookReceived = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        queue.subscribe(DataType.KLINE, data -> { klineReceived.add(data); latch.countDown(); });
        queue.subscribe(DataType.ORDER_BOOK, data -> { orderbookReceived.add(data); });

        queue.start();
        queue.publish(testCandle());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(1, klineReceived.size());
        assertEquals(0, orderbookReceived.size());

        queue.stop();
    }
}
