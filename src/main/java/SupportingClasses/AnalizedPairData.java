package SupportingClasses;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Pun Intended in the Name...
public class AnalizedPairData implements Comparable<AnalizedPairData> {

    public final String pairKeyForMapper;
    public final PairData exchangeA, exchangeB;
    private final String borrowTokenSymbol, repayTokenSymbol; // W.r.t. Ex. A
    private final String borrowToken, repayToken; // W.r.t. Ex. A
    private final int exchangeAIndex, exchangeBIndex;

    public final BigDecimal maxPossibleProfit, maxBorrowAmount, repayTokenDerivedETH, maxProfitInETH;

    public AnalizedPairData(String pairKeyForMapper, String borrowTokenSymbol, String repayTokenSymbol, String borrowToken, String repayToken,
                            BigDecimal repayTokenDerivedETH, PairData exchangeA, PairData exchangeB, BigDecimal maxPossibleProfit,
                            BigDecimal maxBorrowAmount, int exchangeAIndex, int exchangeBIndex) {
        this.pairKeyForMapper = pairKeyForMapper;
        this.borrowTokenSymbol = borrowTokenSymbol;
        this.repayTokenSymbol = repayTokenSymbol;
        this.borrowToken = borrowToken;
        this.repayToken = repayToken;
        this.repayTokenDerivedETH = repayTokenDerivedETH;
        this.exchangeA = exchangeA;
        this.exchangeB = exchangeB;
        this.maxPossibleProfit = maxPossibleProfit.setScale(8, RoundingMode.HALF_DOWN);
        this.maxBorrowAmount = maxBorrowAmount.setScale(8, RoundingMode.HALF_DOWN);
        this.exchangeAIndex = exchangeAIndex;
        this.exchangeBIndex = exchangeBIndex;
        maxProfitInETH = maxPossibleProfit.multiply(repayTokenDerivedETH).setScale(18, RoundingMode.HALF_DOWN);
    }

    @Override
    public int compareTo(@NotNull AnalizedPairData o) {
        return maxPossibleProfit.compareTo(o.maxPossibleProfit);
    }

    @Override
    public String toString() {
        return "," + borrowTokenSymbol + "," + repayTokenSymbol + ", " + (exchangeAIndex + 1) + ", " + (exchangeBIndex + 1) + ","
                + maxBorrowAmount + "," + maxPossibleProfit + ", " + maxProfitInETH;
    }
}
