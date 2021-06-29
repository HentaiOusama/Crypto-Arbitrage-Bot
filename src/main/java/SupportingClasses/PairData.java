package SupportingClasses;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class PairData {
    public final String pairId, token0Id, token1Id, token0Symbol, token1Symbol;
    private BigDecimal token0Volume, token1Volume, token0StaticPrice, token1StaticPrice;

    public PairData(String pairId, String token0Id, String token1Id, String token0Symbol, String token1Symbol) {
        this.pairId = pairId;
        this.token0Id = token0Id;
        this.token1Id = token1Id;
        this.token0Symbol = token0Symbol;
        this.token1Symbol = token1Symbol;
        token0Volume = BigDecimal.valueOf(0);
        token1Volume = BigDecimal.valueOf(0);
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
        return ">\nPair Id: " + pairId + ", token0: " + token0Id + ", token1: " + token1Id + "\nVolume0: " + token0Volume + ", Volume1: " + token1Volume
                + ", StaticPrice0: " + token0StaticPrice + ", StaticPrice1: " + token1StaticPrice + "\n";
    }
}
