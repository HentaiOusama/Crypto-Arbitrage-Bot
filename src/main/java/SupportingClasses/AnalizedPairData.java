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

    public final BigDecimal maxPossibleProfit, maxBorrowAmount;

    public AnalizedPairData(String pairKeyForMapper, String borrowTokenSymbol, String repayTokenSymbol, String borrowToken, String repayToken,
                            PairData exchangeA, PairData exchangeB, BigDecimal maxPossibleProfit, BigDecimal maxBorrowAmount,
                            int exchangeAIndex, int exchangeBIndex) {
        this.pairKeyForMapper = pairKeyForMapper;
        this.borrowTokenSymbol = borrowTokenSymbol;
        this.repayTokenSymbol = repayTokenSymbol;
        this.borrowToken = borrowToken;
        this.repayToken = repayToken;
        this.exchangeA = exchangeA;
        this.exchangeB = exchangeB;
        this.maxPossibleProfit = maxPossibleProfit.setScale(5, RoundingMode.HALF_DOWN);
        this.maxBorrowAmount = maxBorrowAmount.setScale(5, RoundingMode.HALF_DOWN);
        this.exchangeAIndex = exchangeAIndex;
        this.exchangeBIndex = exchangeBIndex;
    }

    @Override
    public int compareTo(@NotNull AnalizedPairData o) {
        return maxPossibleProfit.compareTo(o.maxPossibleProfit);
    }

    @Override
    public String toString() {
        return "," + borrowTokenSymbol + "," + repayTokenSymbol + ", " + (exchangeAIndex + 1) + ", " + (exchangeBIndex + 1) + ","
                + maxPossibleProfit + "," + maxBorrowAmount;
    }
}
