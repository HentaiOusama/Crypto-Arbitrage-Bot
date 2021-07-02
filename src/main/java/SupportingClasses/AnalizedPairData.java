package SupportingClasses;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Pun Intended in the Name...
public class AnalizedPairData implements Comparable<AnalizedPairData> {

    public final String pairKeyForMapper;
    public final PairData exchangeA, exchangeB;
    private final String sellTokenSymbol, buyTokenSymbol; // W.r.t. Ex. A
    private final String sellToken, buyToken; // W.r.t. Ex. A
    private final int exchangeAIndex, exchangeBIndex;

    public final BigDecimal maxPossibleProfit, maxSellAmount;

    public AnalizedPairData(String pairKeyForMapper, String sellTokenSymbol, String buyTokenSymbol, String sellToken, String buyToken,
                            PairData exchangeA, PairData exchangeB, BigDecimal maxPossibleProfit, BigDecimal maxSellAmount,
                            int exchangeAIndex, int exchangeBIndex) {
        this.pairKeyForMapper = pairKeyForMapper;
        this.sellTokenSymbol = sellTokenSymbol;
        this.buyTokenSymbol = buyTokenSymbol;
        this.sellToken = sellToken;
        this.buyToken = buyToken;
        this.exchangeA = exchangeA;
        this.exchangeB = exchangeB;
        this.maxPossibleProfit = maxPossibleProfit.setScale(5, RoundingMode.HALF_DOWN);
        this.maxSellAmount = maxSellAmount.setScale(5, RoundingMode.HALF_DOWN);
        this.exchangeAIndex = exchangeAIndex;
        this.exchangeBIndex = exchangeBIndex;
    }

    @Override
    public int compareTo(@NotNull AnalizedPairData o) {
        return maxPossibleProfit.compareTo(o.maxPossibleProfit);
    }

    @Override
    public String toString() {
        return "," + buyTokenSymbol + "," + sellTokenSymbol + ", " + (exchangeAIndex + 1) + ", " + (exchangeBIndex + 1) + ","
                + maxPossibleProfit + "," + maxSellAmount;
    }
}
