package com.enarsee.marketdata.ingestor;

import com.enarsee.marketdata.model.DataType;
import com.enarsee.marketdata.model.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Decouples the ingestor (WebSocket) thread from consumer (persistor) threads via a
 * BlockingQueue, solving two problems:
 *
 *   1. Thread isolation: the WS callback thread is never blocked by slow persistence
 *      (e.g., disk I/O, future network storage), preventing message backpressure
 *      from affecting the live stream.
 *
 *   2. Filtered fan-out: multiple consumers subscribe with a DataType filter, so each
 *      persistor only receives the data types it handles. A KLINE persistor won't
 *      receive ORDER_BOOK data and vice versa — they get separate files/destinations
 *      without any routing logic in the persistor itself.
 *
 * A single dispatcher thread polls the queue and fans out to matching subscribers.
 */
public class MarketDataQueue {
    private static final Logger log = LoggerFactory.getLogger(MarketDataQueue.class);
    private final BlockingQueue<MarketData> queue = new LinkedBlockingQueue<>();
    private final List<FilteredSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcherExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "market-data-dispatcher");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = false;

    public void subscribe(DataType dataType, Consumer<MarketData> consumer) {
        subscribers.add(new FilteredSubscriber(dataType, consumer));
    }

    public void publish(MarketData data) {
        queue.offer(data);
    }

    public void start() {
        running = true;
        dispatcherExecutor.submit(() -> {
            while (running) {
                try {
                    MarketData data = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (data != null) {
                        for (FilteredSubscriber sub : subscribers) {
                            if (sub.dataType == data.dataType()) {
                                try {
                                    sub.consumer.accept(data);
                                } catch (Exception e) {
                                    log.error("Subscriber error", e);
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    public void stop() {
        running = false;
        dispatcherExecutor.shutdown();
    }

    private record FilteredSubscriber(DataType dataType, Consumer<MarketData> consumer) {}
}
