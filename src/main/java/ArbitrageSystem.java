import SupportingClasses.AnalizedPairData;
import SupportingClasses.PairData;
import SupportingClasses.TheGraphQueryMaker;
import SupportingClasses.TokenIdToPairIdMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;

/*
 * Requires Environment Variable :-
 * 1. HttpsEndpoint : Https Url from node hosting endpoint services such as Infura, QuickNode, etc
 * -----------------------------------------------------------------------------------------
 *
 * System Assumptions: -
 * (When not explicitly mentioned, or understood, then the following indexes mean 👇)
 * 0 => UniSwap
 * 1 => SushiSwap
 * 2 => DefiSwap
 * ------------------------------------------------------------------------------------------
 * */
public class ArbitrageSystem {

    // Manager Variables
    private final ScheduledExecutorService coreSystemExecutorService = Executors.newSingleThreadScheduledExecutor();
    public int waitTimeInMillis, count = 29;
    private final ArbitrageTelegramBot arbitrageTelegramBot;
    private final Semaphore mutex = new Semaphore(1);

    // Web3 Related Variables
    private Web3j web3j;

    // Crypto Related Variables
    private final ArrayList<String> allExchangesToMonitor = new ArrayList<>();
    private final String arbitrageContractAddress;
    private final ArrayList<TheGraphQueryMaker> allQueryMakers = new ArrayList<>();
    // Mapping TheGraphQueryMaker to a mapping of pairId mapped to PairData 👇
    private final HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData = new HashMap<>();
    private final TokenIdToPairIdMapper tokenIdToPairIdMapper = new TokenIdToPairIdMapper();
    private final ArrayList<AnalizedPairData> allAnalizedPairData = new ArrayList<>();


    ArbitrageSystem(ArbitrageTelegramBot arbitrageTelegramBot, String arbitrageContractAddress, int waitTimeInMillis,
                    String[] dexTheGraphHostUrls, String[][][] allPairIdsOnAllNetworks) {
        this.arbitrageTelegramBot = arbitrageTelegramBot;
        this.arbitrageContractAddress = arbitrageContractAddress;
        this.waitTimeInMillis = waitTimeInMillis;
        this.allExchangesToMonitor.addAll(Arrays.asList(dexTheGraphHostUrls));

        int length = dexTheGraphHostUrls.length;
        for (int i = 0; i < length; i++) {
            TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker(dexTheGraphHostUrls[i], MainClass.logPrintStream);
            allQueryMakers.add(theGraphQueryMaker);
            HashMap<String, PairData> hashMap = new HashMap<>();
            allNetworkAllPairData.put(theGraphQueryMaker, hashMap);

            buildGraphQLQuery(i, theGraphQueryMaker, hashMap, allPairIdsOnAllNetworks[i]);
        }
    }

    protected void buildGraphQLQuery(int index, TheGraphQueryMaker theGraphQueryMaker, HashMap<String, PairData> hashMap,
                                     String[][] currentNetworkPairIds) {
        int len = currentNetworkPairIds.length;
        if (len == 0) {
            theGraphQueryMaker.isQueryMakerBad = true;
            return;
        }
        StringBuilder stringBuilder = new StringBuilder("[");
        for (int j = 0; j < len; j++) {
            if (currentNetworkPairIds[j][0] == null) {
                continue;
            }
            tokenIdToPairIdMapper.addPairTracker(currentNetworkPairIds[j][1], currentNetworkPairIds[j][2], currentNetworkPairIds[j][0]);
            hashMap.put(currentNetworkPairIds[j][0], new PairData(index, currentNetworkPairIds[j][0], currentNetworkPairIds[j][1],
                    currentNetworkPairIds[j][2], currentNetworkPairIds[j][3], currentNetworkPairIds[j][4], currentNetworkPairIds[j][5],
                    currentNetworkPairIds[j][6]));
            stringBuilder.append("\"").append(currentNetworkPairIds[j][0]).append("\"");
            if (j < len - 1) {
                stringBuilder.append(", ");
            }
        }
        stringBuilder.append("]");

        theGraphQueryMaker.setGraphQLQuery(String.format("""
                {
                   pairs(where: {id_in: %s}) {
                     id
                     reserve0
                     reserve1
                   }
                }""", stringBuilder)
        );
    }

    public ArrayList<String> getPairDetails(String token0, String token1) throws Exception {
        if (token0.equalsIgnoreCase(token1)) {
            throw new Exception("Both Token Ids cannot be same....");
        }
        token0 = token0.toLowerCase();
        token1 = token1.toLowerCase();

        // This process is same that is adopted by uniswap when creating a pair....
        if (token0.compareTo(token1) > 0) {
            String temp = token0;
            token0 = token1;
            token1 = temp;
        }

        ArrayList<String> retVal = new ArrayList<>();
        /*Index: -
         * 0 => Token0 Symbol
         * 1 => Token1 Symbol
         * 2...N => PairIds on Different Dex
         * */

        TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker(allExchangesToMonitor.get(0), MainClass.logPrintStream);
        theGraphQueryMaker.setGraphQLQuery(String.format("""
                {
                    pairs(where: {token0: "%s", token1: "%s"}) {
                        id
                        token0 {
                            symbol
                            decimals
                        }
                        token1 {
                            symbol
                            decimals
                        }
                    }
                }""", token0, token1));

        JSONObject jsonObject = theGraphQueryMaker.sendQuery();
        if (jsonObject == null) {
            throw new Exception("Error while sending the query...");
        }
        JSONArray jsonArray = jsonObject.getJSONArray("pairs");
        if (jsonArray.length() == 0) {
            throw new Exception("Invalid Token Ids...");
        }
        jsonObject = jsonArray.getJSONObject(0);
        retVal.add(token0.toLowerCase());
        retVal.add(token1.toLowerCase());
        retVal.add(jsonObject.getJSONObject("token0").getString("symbol"));
        retVal.add(jsonObject.getJSONObject("token1").getString("symbol"));
        retVal.add(jsonObject.getJSONObject("token0").getString("decimals"));
        retVal.add(jsonObject.getJSONObject("token1").getString("decimals"));

        for (String host : allExchangesToMonitor) {
            theGraphQueryMaker = new TheGraphQueryMaker(host, MainClass.logPrintStream);
            theGraphQueryMaker.setGraphQLQuery(String.format("""
                    {
                        pairs(where: {token0: "%s", token1: "%s"}) {
                            id
                        }
                    }""", token0, token1));

            jsonObject = theGraphQueryMaker.sendQuery();
            if (jsonObject == null) {
                throw new Exception("Error while sending the query...");
            }
            jsonArray = jsonObject.getJSONArray("pairs");
            if (jsonArray.length() == 1) {
                retVal.add(jsonArray.getJSONObject(0).getString("id"));
            } else {
                retVal.add("");
            }
        }

        return retVal;
    }

    public ArrayList<String> removePair(String token0, String token1) {
        String key = tokenIdToPairIdMapper.getKey(token0, token1);
        ArrayList<String> retVal = null;

        if (key != null) {
            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace(MainClass.logPrintStream);
                return null;
            }

            try {
                retVal = tokenIdToPairIdMapper.get(key);

                int length = retVal.size();
                for (int i = 0; i < length; i++) {
                    String currentPairId = retVal.get(i);
                    if (!currentPairId.equalsIgnoreCase("")) {
                        allNetworkAllPairData.get(allQueryMakers.get(i)).remove(currentPairId);
                    }
                }

                tokenIdToPairIdMapper.remove(key);
            } finally {
                mutex.release();
            }
        }

        return retVal;
    }

    public void shutdownWeb3j() {
        if (web3j != null) {
            web3j.shutdown();
        }
    }

    public void buildWeb3j() {
        shutdownWeb3j();

        web3j = Web3j.build(new HttpService(System.getenv("HttpsEndpoint")));

        try {
            MainClass.logPrintStream.println("Web3 Client Version : " + web3j.web3ClientVersion().send().getWeb3ClientVersion());
        } catch (Exception e) {
            e.printStackTrace(MainClass.logPrintStream);
        }
    }

    public void startSystem() {
        MainClass.logPrintStream.println("Arbitrage System Running Now...");
        System.out.println("Arbitrage System Running Now...");

        coreSystemExecutorService.scheduleWithFixedDelay(new CoreSystem(), 0, waitTimeInMillis, TimeUnit.MILLISECONDS);
    }

    public void stopSystem() {
        try {
            mutex.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace(MainClass.logPrintStream);
        }
        try {
            coreSystemExecutorService.shutdown();
            try {
                if (!coreSystemExecutorService.awaitTermination(10, TimeUnit.SECONDS) && !coreSystemExecutorService.isShutdown()) {
                    coreSystemExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                e.printStackTrace(MainClass.logPrintStream);
                if (!coreSystemExecutorService.isShutdown()) {
                    coreSystemExecutorService.shutdownNow();
                }
            }
            shutdownWeb3j();
        } finally {
            mutex.release();
        }

        MainClass.logPrintStream.println("Arbitrage System Stopped Running...");
        System.out.println("Arbitrage System Stopped Running...");
    }

    protected void printAllDeterminedData(PrintStream... printStreams) {
        for (PrintStream printStream : printStreams) {
            printStream.println("""
                    <-----     Printing All Determined Data     ----->
                                        
                                        
                                        
                    Pair Id,Token 0 Symbol,Token 1 Symbol,Token 0 Volume,Token 1 Volume,Token 0 StaticPrice,Token 1 StaticPrice,Last Update Time,Exchange No.,Network Name
                    """);
        }

        int hostCounter = 0;
        for (TheGraphQueryMaker theGraphQueryMaker : allQueryMakers) {
            hostCounter++;
            HashMap<String, PairData> currentNetworkPair = allNetworkAllPairData.get(theGraphQueryMaker);
            Set<String> keys = currentNetworkPair.keySet();

            StringBuilder allPairDataInCSVFormat = new StringBuilder();
            for (String key : keys) {
                allPairDataInCSVFormat
                        .append(currentNetworkPair.get(key))
                        .append(",")
                        .append(hostCounter)
                        .append(",")
                        .append(theGraphQueryMaker.getHostUrl())
                        .append("\n");
            }

            for (PrintStream printStream : printStreams) {
                printStream.print(allPairDataInCSVFormat);
                printStream.println("--------,--------,--------,--------,--------,--------,--------,--------,--------");
            }
        }

        for (PrintStream printStream : printStreams) {
            printStream.println("""
                                        
                                        
                    <-----     Trimmed Data After Analysis     ----->
                                        
                    ,Borrow Token Symbol,Repay Token Symbol,Exchange A,Exchange B,Max Possible Profit,Max Borrow Amount
                    """);

            for (AnalizedPairData analizedPairData : allAnalizedPairData) {
                printStream.println(analizedPairData);
            }

            printStream.println("""
                                        
                                        
                    Notes: -
                    Borrow Token means the token we borrow from Exchange A and sell on Exchange B.
                    Repay Token means the token we repay to Exchange A that we get from Exchange B.
                    Max. profit is in terms of repay token.
                                        
                                        
                                        
                    <-----     Data Printing Complete     ----->""");
        }
    }

    public boolean printAllDeterminedData(String chatId) {
        try {
            File file = new File("GatheredData.csv");
            if (!file.exists()) {
                if (!file.createNewFile()) {
                    return false;
                }
            }
            FileOutputStream fileOutputStream = new FileOutputStream("GatheredData.csv");
            PrintStream printStream = new PrintStream(fileOutputStream);
            printAllDeterminedData(printStream);
            printStream.close();
            fileOutputStream.close();
            arbitrageTelegramBot.sendFile(chatId, "GatheredData.csv");
            return true;
        } catch (IOException e) {
            e.printStackTrace(MainClass.logPrintStream);
            return false;
        }
    }

    private void makeQueriesAndSetData() {
        for (TheGraphQueryMaker theGraphQueryMaker : allQueryMakers) {

            JSONObject jsonObject = theGraphQueryMaker.sendQuery();

            if (jsonObject != null) {

                JSONArray allPairs = jsonObject.getJSONArray("pairs");
                PairData pairData;

                for (int i = 0; i < allPairs.length(); i++) {
                    jsonObject = allPairs.getJSONObject(i);
                    pairData = allNetworkAllPairData.get(theGraphQueryMaker).get(jsonObject.getString("id"));
                    if (pairData != null) {
                        pairData.setTokenVolumes(jsonObject.getString("reserve0"), jsonObject.getString("reserve1"));
                    }
                }
            }
        }
    }

    public BigDecimal[] getMaximumPossibleProfitAndBorrowAmount(final BigDecimal volumeOfT0OnExA, final BigDecimal volumeOfT1OnExA,
                                                                final BigDecimal volumeOfT0OnExB, final BigDecimal volumeOfT1OnExB) {

        /*
         * The calculations are based on the following procedure: -
         * 1) We borrow B1 amount of coin 1 from Ex. A
         * 2) Sell those B1 amount coin 1 on Ex. B
         * 3) We receive S2 amount of coin 2 from Ex. B
         * 4) We return R2 amount of coin 2 to Ex. A
         * 5) Profit = S2 - R2
         *
         * But, this procedure cannot be actually used for flash loans.
         * */

        final BigDecimal val0 = BigDecimal.valueOf(0);

        /*
         * Holders (Initialized to 0)
         * Index 0 => Profit
         * Index 1 => Borrow Amount
         * */
        BigDecimal[] result = new BigDecimal[]{val0, val0};

        // Step 1
        if (volumeOfT0OnExA.compareTo(val0) <= 0 || volumeOfT0OnExB.compareTo(val0) <= 0
                || volumeOfT1OnExA.compareTo(val0) <= 0 || volumeOfT1OnExB.compareTo(val0) <= 0) {
            return result;
        } else if ((volumeOfT1OnExA.divide(volumeOfT0OnExA, RoundingMode.HALF_DOWN))
                .compareTo(volumeOfT1OnExB.divide(volumeOfT0OnExB, RoundingMode.HALF_DOWN)) >= 0) {
            return result;
        }

        // Step 2
        BigDecimal _0B_X_1B_ = volumeOfT0OnExB.multiply(volumeOfT1OnExB);
        BigDecimal _0A_X_1A_ = volumeOfT0OnExA.multiply(volumeOfT1OnExA);
        BigDecimal denominator = _0B_X_1B_.subtract(_0A_X_1A_);

        // Pre-calculations
        BigDecimal _S_1A1B_ = volumeOfT1OnExA.add(volumeOfT1OnExB);
        boolean singleBorrowMode = true;
        BigDecimal B11 = null, B12 = null;

        // if Step 3 else (Step 4 & Step 5)
        if (denominator.compareTo(val0) == 0) {
            BigDecimal B1 = (volumeOfT0OnExA.multiply(volumeOfT1OnExB)).subtract(volumeOfT0OnExB.multiply(volumeOfT1OnExA));
            B1 = B1.divide(_S_1A1B_, RoundingMode.HALF_EVEN);

            if ((B1.compareTo(val0) <= 0) || (B1.compareTo(volumeOfT0OnExA) >= 0)) {
                return result;
            } else {
                result[1] = B1;
            }
        } else {
            BigDecimal numeratorLeft = _S_1A1B_.multiply(volumeOfT0OnExA.multiply(volumeOfT0OnExB));
            BigDecimal numeratorRight = volumeOfT0OnExA.add(volumeOfT0OnExB);
            numeratorRight = numeratorRight.multiply((_0A_X_1A_.multiply(_0B_X_1B_)).sqrt(new MathContext(18)));

            B11 = numeratorLeft.add(numeratorRight);
            B12 = numeratorLeft.subtract(numeratorRight);
            B11 = B11.divide(denominator, RoundingMode.HALF_EVEN);
            B12 = B12.divide(denominator, RoundingMode.HALF_EVEN);
            boolean a = (B11.compareTo(val0) > 0) && (B11.compareTo(volumeOfT0OnExA) < 0),
                    b = (B12.compareTo(val0) > 0) && (B12.compareTo(volumeOfT0OnExA) < 0);

            if (a && b) {
                singleBorrowMode = false;
            } else if (a) {
                result[1] = B11;
            } else if (b) {
                result[1] = B12;
            } else {
                return result;
            }
        }

        // Step 6
        if (singleBorrowMode) {
            BigDecimal P = (volumeOfT1OnExB.multiply(result[1]).divide(volumeOfT0OnExB.add(result[1]), RoundingMode.HALF_EVEN))
                    .subtract(volumeOfT1OnExA.multiply(result[1]).divide(volumeOfT0OnExA.subtract(result[1]), RoundingMode.HALF_EVEN));

            if ((P.compareTo(val0) > 0) && (P.compareTo(volumeOfT1OnExB) < 0)) {
                result[0] = P;
            }
        } else {
            BigDecimal P1 = (volumeOfT1OnExB.multiply(B11).divide(volumeOfT0OnExB.add(B11), RoundingMode.HALF_EVEN))
                    .subtract(volumeOfT1OnExA.multiply(B11).divide(volumeOfT0OnExA.subtract(B11), RoundingMode.HALF_EVEN));
            BigDecimal P2 = (volumeOfT1OnExB.multiply(B12).divide(volumeOfT0OnExB.add(B12), RoundingMode.HALF_EVEN))
                    .subtract(volumeOfT1OnExA.multiply(B12).divide(volumeOfT0OnExA.subtract(B12), RoundingMode.HALF_EVEN));

            if (P1.compareTo(val0) > 0 && P1.compareTo(volumeOfT1OnExB) < 0) {
                result[0] = P1;
                result[1] = B11;
            }

            if (P2.compareTo(val0) > 0 && P2.compareTo(volumeOfT1OnExB) < 0 && P2.compareTo(result[0]) > 0) {
                result[0] = P2;
                result[1] = B11;
            }
        }

        return result;
    }

    @Deprecated
    public BigDecimal[] getMaximumPossibleProfitAndSellAmountObsolete(BigDecimal volumeOfT0OnExA, BigDecimal volumeOfT1OnExA,
                                                                      BigDecimal volumeOfT0OnExB, BigDecimal volumeOfT1OnExB) throws Exception {

        /*
         * The calculations are based on the following procedure: -
         * 1) Sell Token 0 on Ex. A
         * 2) Receive Token 1 from Ex. A
         * 3) Sell Token 1 on Ex. B
         * 4) Receive Token 0 from Ex. B
         *
         * But, this procedure cannot be actually used for flash loans.
         * */

        BigDecimal[] retVal = new BigDecimal[2];

        if (!(volumeOfT1OnExA.divide(volumeOfT0OnExA, RoundingMode.HALF_DOWN).compareTo(volumeOfT1OnExB.divide(volumeOfT0OnExB, RoundingMode.HALF_DOWN)) >= 0)) {
            throw new Exception("Price on Token 0 on Exchange A must be more or equal to that on the Exchange B");
        }

        MathContext mathContext = new MathContext(10);

        BigDecimal _0A_X_1B_ = volumeOfT0OnExA.multiply(volumeOfT1OnExB);
        BigDecimal _0B_X_1A_ = volumeOfT0OnExB.multiply(volumeOfT1OnExA);

        BigDecimal sellAmount = _0A_X_1B_.multiply(_0B_X_1A_).sqrt(mathContext);
        sellAmount = sellAmount.subtract(_0A_X_1B_);
        sellAmount = sellAmount.divide(volumeOfT1OnExA.add(volumeOfT1OnExB), RoundingMode.HALF_DOWN);

        retVal[1] = sellAmount;

        BigDecimal temp;
        BigDecimal maxPossibleProfit = _0B_X_1A_.multiply(sellAmount);
        temp = _0A_X_1B_.add(volumeOfT1OnExB.multiply(sellAmount)).add(volumeOfT1OnExA.multiply(sellAmount));
        maxPossibleProfit = (maxPossibleProfit.divide(temp, RoundingMode.HALF_DOWN)).subtract(sellAmount);

        retVal[0] = maxPossibleProfit;

        return retVal;
    }

    private void analyseAllPairsForArbitragePossibility() {

        allAnalizedPairData.clear();
        ExecutorService pairAnalysingExecutorService = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
        ExecutorCompletionService<AnalizedPairData> executorCompletionService = new ExecutorCompletionService<>(pairAnalysingExecutorService);
        int jobCount = 0;
        Set<String> keys = tokenIdToPairIdMapper.keySet();
        for (String key : keys) {
            ArrayList<String> allPairIdsForSpecificPair = tokenIdToPairIdMapper.get(key);
            int len = allPairIdsForSpecificPair.size();
            if (len <= 0) {
                // Hopefully, we will never enter this block, but just in case.
                continue;
            }

            // borrowMode 1 => Token 0 Static Price <= 1 & borrowMode 2 => Token 0 Static Price > 1
            int borrowMode = (allNetworkAllPairData.get(allQueryMakers.get(0)).get(allPairIdsForSpecificPair.get(0))
                    .getToken0StaticPrice().compareTo(BigDecimal.valueOf(1)) <= 0) ? 1 : 2;

            executorCompletionService.submit(new PairAnalizer(borrowMode, allPairIdsForSpecificPair));
            jobCount++;
        }

        for (int i = 0; i < jobCount; i++) {
            try {
                AnalizedPairData analizedPairData = executorCompletionService.take().get();
                if (analizedPairData != null) {
                    // Not yet complete... Might add a check that profit > gasFees
                    allAnalizedPairData.add(analizedPairData);
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
        }

        pairAnalysingExecutorService.shutdown();
        try {
            if (!pairAnalysingExecutorService.awaitTermination(5, TimeUnit.SECONDS) && !pairAnalysingExecutorService.isShutdown()) {
                pairAnalysingExecutorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            e.printStackTrace(MainClass.logPrintStream);
            if (!pairAnalysingExecutorService.isShutdown()) {
                pairAnalysingExecutorService.shutdownNow();
            }
        }

        Collections.sort(allAnalizedPairData);
    }

    public Set<String> getAllUniSwapPairIds() {
        return allNetworkAllPairData.get(allQueryMakers.get(0)).keySet();
    }

    private class CoreSystem implements Runnable {

        @Override
        public void run() {
            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
            try {
                makeQueriesAndSetData();
                analyseAllPairsForArbitragePossibility();
                if (count == 0) {
                    printAllDeterminedData(MainClass.logPrintStream);
                    count = 29;
                } else {
                    count--;
                }
            } finally {
                mutex.release();
            }
        }
    }

    // Pun Intended...
    private class PairAnalizer implements Callable<AnalizedPairData> {

        private final int borrowMode;
        private final ArrayList<String> allPairIds;

        PairAnalizer(int borrowMode, ArrayList<String> allPairIds) {
            assert (borrowMode == 1) || (borrowMode == 2);
            this.borrowMode = borrowMode;
            this.allPairIds = allPairIds;
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

                            result = getMaximumPossibleProfitAndBorrowAmount(exchangeA.token0Volume, exchangeA.token1Volume,
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

                            result = getMaximumPossibleProfitAndBorrowAmount(exchangeA.token1Volume, exchangeA.token0Volume,
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
                        e.printStackTrace(MainClass.logPrintStream);
                    }
                }
            }

            if (borrowExchange != null) {
                if (borrowMode == 1) {
                    borrowTokenSymbol = borrowExchange.token0Symbol;
                    repayTokenSymbol = borrowExchange.token1Symbol;
                    borrowTokenId = borrowExchange.token0Id;
                    repayTokenId = borrowExchange.token1Id;

                } else {
                    borrowTokenSymbol = borrowExchange.token1Symbol;
                    repayTokenSymbol = borrowExchange.token0Symbol;
                    borrowTokenId = borrowExchange.token1Id;
                    repayTokenId = borrowExchange.token0Id;

                }
                return new AnalizedPairData(borrowExchange.token0Symbol + ", " + borrowExchange.token1Symbol,
                        borrowTokenSymbol, repayTokenSymbol, borrowTokenId, repayTokenId, borrowExchange, sellExchange,
                        currentProfit, currentBorrowAmount, borrowExchangeIndex, sellExchangeIndex);

            } else {
                return null;
            }
        }
    }
}
