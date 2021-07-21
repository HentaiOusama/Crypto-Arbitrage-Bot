package SupportingClasses;


import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Callable;

// Pun Intended...
public class PairAnalizer implements Callable<AnalizedPairData> {

    private final int borrowMode;
    private final ArrayList<String> allPairIds;
    private final ArrayList<TheGraphQueryMaker> allQueryMakers;
    private final HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData;
    private final PrintStream logPrintStream;

    public PairAnalizer(int borrowMode, ArrayList<String> allPairIds, HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData,
                        ArrayList<TheGraphQueryMaker> allQueryMakers, PrintStream logPrintStream) {
        assert (borrowMode == 1) || (borrowMode == 2);
        this.allQueryMakers = allQueryMakers;
        this.allNetworkAllPairData = allNetworkAllPairData;
        this.borrowMode = borrowMode;
        this.allPairIds = allPairIds;
        this.logPrintStream = logPrintStream;
    }

    @Override
    public AnalizedPairData call() {
        int len = allPairIds.size();
        ArrayList<PairData> pairDataArrayList = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            String pairId = allPairIds.get(i);
            if (!pairId.equalsIgnoreCase("")) {
                pairDataArrayList.add(allNetworkAllPairData.get(allQueryMakers.get(i)).get(pairId));
            }
        }

        len = pairDataArrayList.size();
        String borrowTokenSymbol, repayTokenSymbol, borrowTokenId, repayTokenId;
        BigDecimal currentProfit = BigDecimal.valueOf(0);
        BigDecimal currentBorrowAmount = BigDecimal.valueOf(0);
        BigDecimal repayTokenDerivedETH;
        PairData borrowExchange = null, sellExchange = null;
        int borrowExchangeIndex = -1, sellExchangeIndex = -1;

        // Maximize the profit...
        for (int i = 0; i < len; i++) {
            for (int j = i + 1; j < len; j++) {
                PairData exchangeA = pairDataArrayList.get(i), exchangeB = pairDataArrayList.get(j);
                BigDecimal[] result;
                boolean didSwitch = false;

                try {
                    if (borrowMode == 1) {
                        /*
                         * This borrowMode => Token 0 Static Price <= 1
                         * E.g. => 500 ENJ : 1 ETH
                         * Borrow Token 0 from Ex A
                         * Then sell Token 0 on Ex B to buy Token 1
                         * Then repay Token 1 to Ex A
                         * */
                        if (exchangeA.getToken0StaticPrice().compareTo(exchangeB.getToken0StaticPrice()) > 0) {
                            PairData temp = exchangeA;
                            exchangeA = exchangeB;
                            exchangeB = temp;
                            didSwitch = true;

                            // This makes sure that price of Borrow Token on Ex. A <= Price of Borrow Token on Ex. B
                        }

                        result = ArbitrageCalculator.getMaximumPossibleProfitAndBorrowAmount(exchangeA.token0Volume, exchangeA.token1Volume,
                                exchangeB.token0Volume, exchangeB.token1Volume);
                    } else {
                        /*
                         * This borrowMode => Token 0 Price > 1
                         * E.g. => 1 ETH : 500 ENJ
                         * Borrow Token 1 from Ex A
                         * Then sell Token 1 on Ex B to buy Token 0
                         * THen repay Token 0 to Ex A
                         * */
                        if (exchangeA.getToken1StaticPrice().compareTo(exchangeB.getToken1StaticPrice()) > 0) {
                            PairData temp = exchangeA;
                            exchangeA = exchangeB;
                            exchangeB = temp;
                            didSwitch = true;

                            // This makes sure that price of Borrow token on Ex. A <= Price of Borrow Token on Ex. B
                        }

                        result = ArbitrageCalculator.getMaximumPossibleProfitAndBorrowAmount(exchangeA.token1Volume, exchangeA.token0Volume,
                                exchangeB.token1Volume, exchangeB.token0Volume);
                    }

                    if (result[0].compareTo(currentProfit) > 0) {
                        currentProfit = result[0];
                        currentBorrowAmount = result[1];
                        borrowExchange = exchangeA;
                        sellExchange = exchangeB;

                        if (didSwitch) {
                            borrowExchangeIndex = j;
                            sellExchangeIndex = i;
                        } else {
                            borrowExchangeIndex = i;
                            sellExchangeIndex = j;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace(logPrintStream);
                }
            }
        }

        if (borrowExchange != null) {
            if (borrowMode == 1) {
                borrowTokenSymbol = borrowExchange.token0Symbol;
                repayTokenSymbol = borrowExchange.token1Symbol;
                borrowTokenId = borrowExchange.token0Id;
                repayTokenId = borrowExchange.token1Id;
                repayTokenDerivedETH = borrowExchange.token1DerivedETH;

            } else {
                borrowTokenSymbol = borrowExchange.token1Symbol;
                repayTokenSymbol = borrowExchange.token0Symbol;
                borrowTokenId = borrowExchange.token1Id;
                repayTokenId = borrowExchange.token0Id;
                repayTokenDerivedETH = borrowExchange.token0DerivedETH;
            }

            return new AnalizedPairData(borrowExchange.token0Symbol + ", " + borrowExchange.token1Symbol,
                    borrowTokenSymbol, repayTokenSymbol, borrowTokenId, repayTokenId, repayTokenDerivedETH, borrowExchange, sellExchange,
                    currentProfit, currentBorrowAmount, borrowExchangeIndex, sellExchangeIndex);

        } else {
            return null;
        }
    }
}
