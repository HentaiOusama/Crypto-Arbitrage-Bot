import java.math.BigDecimal;
import java.math.RoundingMode;

public class PairData {
    public final String pairId, token0Id, token1Id;

    public BigDecimal token0Volume, token1Volume;

    PairData(String pairId, String token0Id, String token1Id) {
        this.pairId = pairId;
        this.token0Id = token0Id;
        this.token1Id = token1Id;
        token0Volume = BigDecimal.valueOf(0);
        token1Volume = BigDecimal.valueOf(0);
    }

    public void setToken0Volume(String volume) {
        token0Volume = new BigDecimal(volume);
    }

    public void setToken1Volume(String volume) {
        token1Volume = new BigDecimal(volume);
    }

    public BigDecimal getToken0StaticPrice() {
        return token1Volume.divide(token0Volume, RoundingMode.HALF_DOWN);
    }

    public BigDecimal getToken1StaticPrice() {
        return token0Volume.divide(token1Volume, RoundingMode.HALF_DOWN);
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
        return "Pair Id: " + pairId + "\ntoken0: " + token0Id + "\ntoken1: " + token1Id + "\nVolume0: " + token0Volume + ", Volume1: " + token1Volume
                + ", StaticPrice0: " + getToken0StaticPrice() + ", StaticPrice1: " + getToken1StaticPrice() + "\n";
    }
}
