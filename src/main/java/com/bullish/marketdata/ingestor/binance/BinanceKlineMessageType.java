package com.bullish.marketdata.ingestor.binance;

import com.bullish.marketdata.ingestor.MessageType;
import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.Exchange;
import com.google.gson.Gson;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public class BinanceKlineMessageType implements MessageType<BinanceKlineEvent> {
    private final Gson gson = new Gson();
    private final String symbol;
    private final String interval;

    public BinanceKlineMessageType(String symbol, String interval) {
        this.symbol = symbol;
        this.interval = interval;
    }

    @Override
    public String streamName() {
        return symbol.toLowerCase() + "@kline_" + interval;
    }

    @Override
    public BinanceKlineEvent deserialize(String json) {
        return gson.fromJson(json, BinanceKlineEvent.class);
    }

    @Override
    public Optional<Candlestick> toMarketData(BinanceKlineEvent event) {
        if (!event.kline.closed) {
            return Optional.empty();
        }
        Candlestick candle = new Candlestick(
            Exchange.BINANCE,
            event.kline.symbol,
            Instant.ofEpochMilli(event.kline.openTime),
            Instant.ofEpochMilli(event.kline.closeTime),
            new BigDecimal(event.kline.open),
            new BigDecimal(event.kline.high),
            new BigDecimal(event.kline.low),
            new BigDecimal(event.kline.close),
            new BigDecimal(event.kline.volume)
        );
        return Optional.of(candle);
    }
}
