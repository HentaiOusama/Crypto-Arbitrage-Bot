package SupportingClasses;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Pun Intended in the Name...
public class AnalizedPairData implements Comparable<AnalizedPairData> {

    public final String pairKeyForMapper;
    public final PairData exchangeA, exchangeB;
    public final String borrowToken, repayToken; // W.r.t. Ex. A
    public final String borrowTokenSymbol, repayTokenSymbol; // W.r.t. Ex. A
    private final int exchangeAIndex, exchangeBIndex;
    public final String borrowTokenDecimals, repayTokenDecimals;
    public final int exchangeARouterIndex, exchangeBRouterIndex;

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
        exchangeARouterIndex = this.exchangeA.routerIndex;
        exchangeBRouterIndex = this.exchangeB.routerIndex;
        if (exchangeA.token0Id.equalsIgnoreCase(borrowToken)) {
            borrowTokenDecimals = exchangeA.token0Decimals;
            repayTokenDecimals = exchangeA.token1Decimals;
        } else if (exchangeA.token1Id.equalsIgnoreCase(borrowToken)) {
            borrowTokenDecimals = exchangeA.token1Decimals;
            repayTokenDecimals = exchangeA.token0Decimals;
        } else {
            borrowTokenDecimals = "0";
            repayTokenDecimals = "0";
        }
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
