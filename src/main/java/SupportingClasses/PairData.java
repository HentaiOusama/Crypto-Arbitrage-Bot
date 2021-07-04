package SupportingClasses;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;

public class PairData {
    public final int index;
    public final String pairId, token0Id, token1Id, token0Symbol, token1Symbol, token0Decimals, token1Decimals;
    public BigDecimal token0Volume, token1Volume;
    private BigDecimal token0StaticPrice, token1StaticPrice;
    private Instant lastUpdateMoment;
    private static final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss a");

    public PairData(int index, String pairId, String token0Id, String token1Id, String token0Symbol, String token1Symbol,
                    String token0Decimals, String token1Decimals) {
        this.index = index;
        this.pairId = pairId;
        this.token0Id = token0Id;
        this.token1Id = token1Id;
        this.token0Symbol = token0Symbol;
        this.token1Symbol = token1Symbol;
        this.token0Decimals = token0Decimals;
        this.token1Decimals = token1Decimals;
        token0Volume = BigDecimal.valueOf(0);
        token1Volume = BigDecimal.valueOf(0);
        lastUpdateMoment = Instant.now();
    }

    public void setTokenVolumes(String volume0, String volume1) {
        token0Volume = new BigDecimal(volume0);
        token1Volume = new BigDecimal(volume1);
        calculateAndSetStaticData();
    }

    public BigDecimal getToken0StaticPrice() {
        return token0StaticPrice;
    }

    public BigDecimal getToken1StaticPrice() {
        return token1StaticPrice;
    }

    public void calculateAndSetStaticData() {
        token0StaticPrice = token1Volume.divide(token0Volume, RoundingMode.HALF_DOWN);
        token1StaticPrice = token0Volume.divide(token1Volume, RoundingMode.HALF_DOWN);
        lastUpdateMoment = Instant.now();
    }

    /* Dynamic Amount: -
     * Y * x / (X + x), where:
     * x is your input amount of source token
     * X is the balance of the pool in the source token
     * Y is the balance of the pool in the target token
     * */
    public BigDecimal getDynamicAmountOnToken0Sell(BigDecimal sellAmount) {
        return (token1Volume.multiply(sellAmount)).divide((token0Volume.add(sellAmount)), RoundingMode.HALF_DOWN);
    }

    public BigDecimal getDynamicAmountOnToken1Sell(BigDecimal sellAmount) {
        return (token0Volume.multiply(sellAmount)).divide((token1Volume.add(sellAmount)), RoundingMode.HALF_DOWN);
    }

    @Override
    public String toString() {
        return "" + pairId + "," + token0Symbol + "," + token1Symbol + "," + token0Volume + "," + token1Volume + "," + token0StaticPrice + "," +
                token1StaticPrice + "," + simpleDateFormat.format(Date.from(lastUpdateMoment)) + " (+0:00 UTC)";
    }
}
