import SupportingClasses.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Uint;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.ChainIdLong;
import org.web3j.tx.RawTransactionManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
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
    private final ExecutorService analysisPrinter = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean isPrintingAnalysis = false;
    public int waitTimeInMillis;
    private final ArbitrageTelegramBot arbitrageTelegramBot;
    private final Semaphore mutex = new Semaphore(1);
    private final FileOutputStream fileOutputStream;
    private final PrintStream printStream;
    private boolean hasPrintedAnything = false;
    public volatile BigDecimal thresholdEthAmount;
    private volatile boolean didPerformArbitrage = false;

    // Web3 Related Variables
    private volatile Web3j web3j;

    // Calculation Constants
    private final BigDecimal Z_p_997 = BigDecimal.valueOf(0.997);
    private final BigDecimal Z_p_997_sq = Z_p_997.pow(2);
    private final BigDecimal One_m_Z_p_997_sq = BigDecimal.ONE.subtract(Z_p_997_sq);
    private final BigDecimal val0 = BigDecimal.ZERO;
    private final MathContext mathContextUp = new MathContext(20, RoundingMode.HALF_UP),
            mathContextDown = new MathContext(20, RoundingMode.HALF_DOWN),
            mathContextEven = new MathContext(20, RoundingMode.HALF_EVEN);

    // Crypto Related Variables
    private final String arbitrageContractAddress;
    private final ArrayList<String> allExchangesToMonitor = new ArrayList<>();
    private final ArrayList<TheGraphQueryMaker> allQueryMakers = new ArrayList<>();
    // Mapping TheGraphQueryMaker to a mapping of pairId mapped to PairData 👇 //
    private final HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData = new HashMap<>();
    private final TokenIdToPairIdMapper tokenIdToPairIdMapper = new TokenIdToPairIdMapper();
    private final ArrayList<AnalizedPairData> allAnalizedPairData = new ArrayList<>();
    private final BigInteger gasLimit = new BigInteger("400000");
    private volatile BigDecimal maxGasFees = new BigDecimal(gasLimit.multiply(BigInteger.valueOf(20))) // Default Gas Price set to 20 gwei
            .divide(new BigDecimal("1000000000"), mathContextUp);
    public BigDecimal thresholdLevel;
    private final Credentials credentials;
    public int maxPendingTrxAllowed;
    private final HashMap<String, ExecutedTransactionData> sentTransactionHashAndData = new HashMap<>();


    ArbitrageSystem(ArbitrageTelegramBot arbitrageTelegramBot, String arbitrageContractAddress, String privateKey,
                    int waitTimeInMillis, int maxPendingTrxAllowed, BigDecimal thresholdLevel, String[] dexTheGraphHostUrls,
                    String[][][] allPairIdsOnAllNetworks) throws IllegalArgumentException, IOException {
        this.arbitrageTelegramBot = arbitrageTelegramBot;
        this.arbitrageContractAddress = arbitrageContractAddress;
        this.waitTimeInMillis = waitTimeInMillis;
        this.maxPendingTrxAllowed = maxPendingTrxAllowed;
        this.allExchangesToMonitor.addAll(Arrays.asList(dexTheGraphHostUrls));
        this.thresholdLevel = thresholdLevel;
        try {
            credentials = Credentials.create(privateKey);
        } catch (NumberFormatException e) {
            e.printStackTrace(MainClass.logPrintStream);
            throw new IllegalArgumentException("Invalid Private Key");
        }

        int length = dexTheGraphHostUrls.length;
        for (int i = 0; i < length; i++) {
            TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker(dexTheGraphHostUrls[i], MainClass.logPrintStream);
            allQueryMakers.add(theGraphQueryMaker);
            HashMap<String, PairData> hashMap = new HashMap<>();
            allNetworkAllPairData.put(theGraphQueryMaker, hashMap);

            buildGraphQLQuery(i, theGraphQueryMaker, hashMap, allPairIdsOnAllNetworks[i]);
        }

        File file = new File("ArbitrageResults.csv");
        if (!file.exists()) {
            if (!file.createNewFile()) {
                throw new IOException("Unable to create new file...");
            }
        }
        fileOutputStream = new FileOutputStream(file);
        printStream = new PrintStream(fileOutputStream);
        printStream.println("Transaction Hash,Gas Used By Trx.,Actual Profit Generated,Threshold Eth Amount,Borrow Amount,Expected Profit (Eth)");
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
                    currentNetworkPairIds[j][6], Integer.parseInt(currentNetworkPairIds[j][7])));
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
                     token0 {
                        derivedETH
                     }
                     token1 {
                        derivedETH
                     }
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

    public Set<String> getAllUniSwapPairIds() {
        return allNetworkAllPairData.get(allQueryMakers.get(0)).keySet();
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

    public void startSystem() {
        MainClass.logPrintStream.println("Arbitrage System Running Now...");
        System.out.println("Arbitrage System Running Now...");
        buildWeb3j();
        coreSystemExecutorService.scheduleWithFixedDelay(new CoreSystem(), 0, waitTimeInMillis, TimeUnit.MILLISECONDS);
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

    public void shutdownWeb3j() {
        if (web3j != null) {
            web3j.shutdown();
            web3j = null;
        }
    }

    public void stopSystem(String... chatId) {
        try {
            mutex.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace(MainClass.logPrintStream);
            return;
        }
        try {
            coreSystemExecutorService.shutdownNow();
            getPrintedAnalysisData(false, chatId);
            printStream.close();
            try {
                fileOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
            try {
                boolean shutdownStatus = coreSystemExecutorService.awaitTermination(7, TimeUnit.SECONDS);
                if (!shutdownStatus) {
                    MainClass.logPrintStream.println("Unable to shutdown within 7 seconds");
                }
            } catch (InterruptedException e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
            analysisPrinter.shutdownNow();
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
                                        
                    ,Borrow Token Symbol,Repay Token Symbol,Exchange A,Exchange B,Max. Borrow Amount,Max. Possible Profit,Max. Profit(in ETH)
                    """);

            for (AnalizedPairData analizedPairData : allAnalizedPairData) {
                printStream.println(analizedPairData);
            }

            printStream.println("""
                                        
                                        
                    Notes: -
                    Borrow Token means the token we borrow from Exchange A
                    and sell on Exchange B. Repay Token means the token we
                    repay to Exchange A that we get from Exchange B.
                    Max. Profit is in terms of repay token.
                                        
                                        
                                        
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
            arbitrageTelegramBot.sendFile("GatheredData.csv", chatId);
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
                        pairData.setTokenVolumesAndDerivedETH(
                                jsonObject.getString("reserve0"), jsonObject.getString("reserve1"),
                                jsonObject.getJSONObject("token0").getString("derivedETH"),
                                jsonObject.getJSONObject("token1").getString("derivedETH"));
                    }
                }
            }
        }
    }

    private boolean hasNoPendingTransactions() {
        try {
            BatchRequest batchRequest = web3j.newBatch();
            batchRequest.add(web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.LATEST))
                    .add(web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING));
            List<?> responses = batchRequest.send().getResponses();
            BigInteger nonceComplete = null, noncePending = null;
            Object object = responses.get(0);
            if (object instanceof EthGetTransactionCount) {
                nonceComplete = ((EthGetTransactionCount) object).getTransactionCount();
            }
            object = responses.get(1);
            if (object instanceof EthGetTransactionCount) {
                noncePending = ((EthGetTransactionCount) object).getTransactionCount();
            }

            return noncePending != null && nonceComplete != null && noncePending.compareTo(nonceComplete) == 0;
        } catch (Exception e) {
            e.printStackTrace(MainClass.logPrintStream);
            return false;
        }
    }

    private void analizeAllPairsForArbitragePossibility() {

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
                    .getToken0StaticPrice().compareTo(BigDecimal.ONE) <= 0) ? 1 : 2;

            executorCompletionService.submit(new PairAnalizer(borrowMode, allPairIdsForSpecificPair));
            jobCount++;
        }

        for (int i = 0; i < jobCount; i++) {
            try {
                AnalizedPairData analizedPairData = executorCompletionService.take().get();
                if (analizedPairData != null) {
                    // TODO : Add arbitrage performer here itself...
                    allAnalizedPairData.add(analizedPairData);
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
        }

        pairAnalysingExecutorService.shutdown();
        try {
            if (!pairAnalysingExecutorService.awaitTermination(2, TimeUnit.SECONDS) && !pairAnalysingExecutorService.isShutdown()) {
                pairAnalysingExecutorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            e.printStackTrace(MainClass.logPrintStream);
            if (!pairAnalysingExecutorService.isShutdown()) {
                pairAnalysingExecutorService.shutdownNow();
            }
        }

        Collections.sort(allAnalizedPairData);
        Collections.reverse(allAnalizedPairData);
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
        } else if ((volumeOfT1OnExA.divide(volumeOfT0OnExA, mathContextDown))
                .compareTo(volumeOfT1OnExB.divide(volumeOfT0OnExB, mathContextDown)) >= 0) {
            return result;
        }

        // Step 2
        BigDecimal _0B_X_1B_ = volumeOfT0OnExB.multiply(volumeOfT1OnExB);
        BigDecimal _0A_X_1A_ = volumeOfT0OnExA.multiply(volumeOfT1OnExA);
        BigDecimal denominator = _0B_X_1B_.subtract(_0A_X_1A_);
        denominator = Z_p_997.multiply(denominator);

        // Pre-calculations
        boolean singleBorrowMode = true;
        BigDecimal B11 = null, B12 = null;

        // if Step 3 else (Step 4, Step 5 & Step 6)
        if (denominator.compareTo(val0) == 0) {
            BigDecimal B1 = (volumeOfT0OnExA.multiply(volumeOfT1OnExB).multiply(Z_p_997_sq))
                    .subtract(volumeOfT0OnExB.multiply(volumeOfT1OnExA));
            BigDecimal den = (Z_p_997_sq.multiply(volumeOfT1OnExA)).add(Z_p_997.multiply(volumeOfT1OnExB));
            den = den.multiply(BigDecimal.valueOf(2));
            B1 = B1.divide(den, mathContextEven);

            if ((B1.compareTo(val0) <= 0) || (B1.compareTo(volumeOfT0OnExA) >= 0)) {
                return result;
            } else {
                result[1] = B1;
            }
        } else {
            BigDecimal numeratorLeft = ((Z_p_997.multiply(volumeOfT1OnExA)).add(volumeOfT1OnExB))
                    .multiply(volumeOfT0OnExA.multiply(volumeOfT0OnExB));

            BigDecimal discriminant = ((Z_p_997.multiply(volumeOfT0OnExA)).add(volumeOfT0OnExB)).pow(2);
            discriminant = discriminant.multiply(_0A_X_1A_).multiply(_0B_X_1B_);
            BigDecimal temp = (volumeOfT0OnExA.multiply(volumeOfT0OnExB)).pow(2);
            temp = temp.multiply((volumeOfT1OnExB.pow(2)).subtract(volumeOfT1OnExA.pow(2)));
            temp = temp.multiply(One_m_Z_p_997_sq);
            discriminant = (discriminant.add(temp)).sqrt(mathContextEven);

            // Complex roots => Return 0
            if (discriminant.compareTo(val0) < 0) {
                return result;
            }

            B11 = numeratorLeft.add(discriminant);
            B12 = numeratorLeft.subtract(discriminant);
            B11 = B11.divide(denominator, mathContextEven);
            B12 = B12.divide(denominator, mathContextEven);
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
            BigDecimal P = calculateProfit(volumeOfT0OnExA, volumeOfT1OnExA, volumeOfT0OnExB, volumeOfT1OnExB, result[1]);

            if ((P.compareTo(val0) > 0) && (P.compareTo(volumeOfT1OnExB) < 0)) {
                result[0] = P;
            } else {
                result[1] = val0;
            }
        } else {
            BigDecimal P1 = calculateProfit(volumeOfT0OnExA, volumeOfT1OnExA, volumeOfT0OnExB, volumeOfT1OnExB, B11);
            BigDecimal P2 = calculateProfit(volumeOfT0OnExA, volumeOfT1OnExA, volumeOfT0OnExB, volumeOfT1OnExB, B12);

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

    private BigDecimal calculateProfit(final BigDecimal volumeOfT0OnExA, final BigDecimal volumeOfT1OnExA, final BigDecimal volumeOfT0OnExB,
                                       final BigDecimal volumeOfT1OnExB, final BigDecimal borrowAmount) {
        BigDecimal leftTerm = Z_p_997.multiply(volumeOfT1OnExB).multiply(borrowAmount);
        BigDecimal temp = volumeOfT0OnExB.add(Z_p_997.multiply(borrowAmount));
        leftTerm = leftTerm.divide(temp, mathContextEven);

        BigDecimal rightTerm = volumeOfT1OnExA.multiply(borrowAmount);
        temp = Z_p_997.multiply(volumeOfT0OnExA.subtract(borrowAmount));
        rightTerm = rightTerm.divide(temp, mathContextEven);

        return leftTerm.subtract(rightTerm);
    }

    private void performArbitrage() {
        didPerformArbitrage = false;
        BigInteger gasPrice = null;
        if (allAnalizedPairData.size() != 0) {
            try {
                gasPrice = web3j.ethGasPrice().send().getGasPrice();
                maxGasFees = new BigDecimal(gasLimit.multiply(gasPrice)).divide(new BigDecimal("1000000000000000000"), mathContextUp);
            } catch (IOException e) {
                e.printStackTrace(MainClass.logPrintStream);
                return;
            }
        }

        int pendingTransactionCount = 0;
        for (AnalizedPairData analizedPairData : allAnalizedPairData) {
            if (pendingTransactionCount >= maxPendingTrxAllowed) {
                break;
            }

            thresholdEthAmount = maxGasFees.multiply(thresholdLevel);
            if (analizedPairData.maxProfitInETH.compareTo(thresholdEthAmount) > 0) {

                Function function = new Function(
                        "startArbitrage",
                        Arrays.asList(
                                new Address(analizedPairData.borrowToken),
                                new Address(analizedPairData.repayToken),
                                new Uint((analizedPairData.maxBorrowAmount.multiply(
                                        BigDecimal.TEN.pow(Integer.parseInt(analizedPairData.borrowTokenDecimals))
                                )).toBigInteger()),
                                new Uint(BigInteger.valueOf(0)),
                                new Address(analizedPairData.exchangeA.pairId),
                                new Uint256(BigInteger.valueOf(analizedPairData.exchangeARouterIndex)),
                                new Uint256(BigInteger.valueOf(analizedPairData.exchangeBRouterIndex))
                        ),
                        Collections.emptyList()
                );
                String encodedFunction = FunctionEncoder.encode(function);
                RawTransactionManager rawTransactionManager = new RawTransactionManager(
                        web3j,
                        credentials,
                        ChainIdLong.MAINNET
                );

                try {
                    EthCall ethCall = web3j.ethCall(Transaction.createEthCallTransaction(credentials.getAddress(), arbitrageContractAddress, encodedFunction),
                            DefaultBlockParameterName.LATEST).send();
                    if (ethCall.isReverted()) {
                        throw new IOException("Reverted... Function : " + encodedFunction + "\nReason : " + ethCall.getRevertReason());
                    } else {
                        MainClass.logPrintStream.println("Eth Call Success. Value : " + ethCall.getValue());
                    }
                    String trxHash = rawTransactionManager.sendTransaction(gasPrice, gasLimit, arbitrageContractAddress, encodedFunction, BigInteger.ZERO)
                            .getTransactionHash();
                    sentTransactionHashAndData.put(trxHash, new ExecutedTransactionData(
                            trxHash, encodedFunction, gasPrice, analizedPairData.repayTokenSymbol,
                            Integer.parseInt(analizedPairData.repayTokenDecimals), thresholdEthAmount,
                            analizedPairData.maxBorrowAmount, analizedPairData.maxProfitInETH));

                    didPerformArbitrage = true;
                    pendingTransactionCount++;
                } catch (IOException e) {
                    e.printStackTrace(MainClass.logPrintStream);
                }
            }
        }
    }

    public void getPrintedAnalysisData(boolean shouldSentNotifier, String... chatId) {
        if (hasPrintedAnything) {
            printStream.flush();
            arbitrageTelegramBot.sendFile("ArbitrageResults.csv", chatId);
        } else if (shouldSentNotifier) {
            for (String id : chatId) {
                arbitrageTelegramBot.sendMessage(id, "No arbitrage was performed in last 24 Hrs.");
            }
        }
    }

    private class CoreSystem implements Runnable {

        @Override
        public void run() {
            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace(MainClass.logPrintStream);
                return;
            }
            try {
                boolean shouldArbitrage = hasNoPendingTransactions();
                long startTime = System.nanoTime();
                makeQueriesAndSetData();
                long queryTime = System.nanoTime();
                analizeAllPairsForArbitragePossibility();
                long analysisTime = System.nanoTime();

                if (shouldArbitrage) {
                    performArbitrage();
                    long arbitragePerformanceTime = System.nanoTime();
                    if (didPerformArbitrage) {
                        MainClass.logPrintStream.println("Time for Querying : " + (queryTime - startTime) +
                                "\nTime for Analysis : " + (analysisTime - queryTime) + "\nTime for Arbitrage : " +
                                (arbitragePerformanceTime - analysisTime));
                        printAllDeterminedData(MainClass.logPrintStream);
                    }
                }
                if (!(sentTransactionHashAndData.isEmpty() || isPrintingAnalysis || analysisPrinter.isShutdown())) {
                    analysisPrinter.submit(new AnalysisPrinter());
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

    private class AnalysisPrinter implements Runnable {
        @Override
        public void run() {

            isPrintingAnalysis = true;

            if (!sentTransactionHashAndData.isEmpty()) {
                Set<String> keys = sentTransactionHashAndData.keySet();
                for (String hash : keys) {
                    ExecutedTransactionData executedTransactionData = sentTransactionHashAndData.get(hash);

                    try {
                        Optional<TransactionReceipt> optional = web3j.ethGetTransactionReceipt(hash).send().getTransactionReceipt();

                        if (optional.isPresent()) {
                            TransactionReceipt transactionReceipt = optional.get();
                            String output = executedTransactionData.getPrintableData(transactionReceipt);
                            printStream.println(output);
                            sentTransactionHashAndData.remove(hash);
                            hasPrintedAnything = true;
                        }
                    } catch (IOException | IndexOutOfBoundsException e) {
                        e.printStackTrace(MainClass.logPrintStream);
                    }
                }
            }

            isPrintingAnalysis = false;
        }
    }
}
