package com.enarsee.marketdata.ingestor.binance;

import com.google.gson.annotations.SerializedName;

public class BinanceKlineEvent {
    @SerializedName("e") public String eventType;
    @SerializedName("E") public long eventTime;
    @SerializedName("s") public String symbol;
    @SerializedName("k") public BinanceKline kline;
}
