package com.enarsee.marketdata.ingestor.binance;

import com.google.gson.annotations.SerializedName;

public class BinanceKline {
    @SerializedName("t") public long openTime;
    @SerializedName("T") public long closeTime;
    @SerializedName("s") public String symbol;
    @SerializedName("i") public String interval;
    @SerializedName("o") public String open;
    @SerializedName("h") public String high;
    @SerializedName("l") public String low;
    @SerializedName("c") public String close;
    @SerializedName("v") public String volume;
    @SerializedName("x") public boolean closed;
}
