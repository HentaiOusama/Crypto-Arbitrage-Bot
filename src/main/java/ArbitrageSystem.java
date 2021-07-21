import SupportingClasses.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Uint;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
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
 *
 * System Assumptions: -
 * (When not explicitly mentioned, or understood, then the following indexes mean 👇)
 * 0 => UniSwap / PancakeSwap
 * 1 => Dex Depends upon the selected network
 * 2 => -------------- || -------------------
 *
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
    public static volatile BigDecimal thresholdEthAmount;
    private volatile boolean didPerformArbitrage = false;

    // Calculation Constants
    private static final MathContext mathContextUp = new MathContext(20, RoundingMode.HALF_UP);

    // Crypto-Pair Related Storage Variables
    private final String chainName, arbitrageContractAddress;
    private final ArrayList<String> allExchangesToMonitor = new ArrayList<>();
    private final ArrayList<TheGraphQueryMaker> allQueryMakers = new ArrayList<>();
    // Mapping TheGraphQueryMaker to a mapping of pairId mapped to PairData 👇 //
    private final HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData = new HashMap<>();
    private final TokenIdToPairIdMapper tokenIdToPairIdMapper = new TokenIdToPairIdMapper();
    private final ArrayList<AnalizedPairData> allAnalizedPairData = new ArrayList<>();
    private volatile BigInteger nonce;
    private volatile int pendingCount = 0;

    // Web3 Related Variables
    private final String web3EndpointUrl;
    private volatile Web3j web3j;
    private RawTransactionManager rawTransactionManager;
    private BigInteger gasPrice = BigInteger.valueOf(20000000000L);  // Default Gas Price set to 20 gwei
    private final BigInteger gasLimit = new BigInteger("400000");
    private volatile BigDecimal maxGasFees = new BigDecimal(gasLimit.multiply(gasPrice))
            .divide(new BigDecimal("1000000000000000000"), mathContextUp);
    public BigDecimal thresholdLevel;
    private final Credentials credentials;
    public int maxPendingTrxAllowed;
    private final HashMap<String, ExecutedTransactionData> sentTransactionHashAndData = new HashMap<>();


    ArbitrageSystem(ArbitrageTelegramBot arbitrageTelegramBot, String chainName, String arbitrageContractAddress, String privateKey,
                    int waitTimeInMillis, int maxPendingTrxAllowed, BigDecimal thresholdLevel, String[] dexTheGraphHostUrls,
                    String[][][] allPairIdsOnAllNetworks, String web3EndpointUrl) throws IllegalArgumentException, IOException {
        this.arbitrageTelegramBot = arbitrageTelegramBot;
        this.chainName = chainName;
        this.arbitrageContractAddress = arbitrageContractAddress;
        this.waitTimeInMillis = waitTimeInMillis;
        this.maxPendingTrxAllowed = maxPendingTrxAllowed;
        this.allExchangesToMonitor.addAll(Arrays.asList(dexTheGraphHostUrls));
        this.thresholdLevel = thresholdLevel;
        this.web3EndpointUrl = web3EndpointUrl;
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

            buildGraphQLQuery(i, true, theGraphQueryMaker, hashMap, allPairIdsOnAllNetworks[i], tokenIdToPairIdMapper);
        }

        File file = new File(chainName + "-ArbitrageResults.csv");
        if (!file.exists()) {
            if (!file.createNewFile()) {
                throw new IOException("Unable to create new file...");
            }
        }
        fileOutputStream = new FileOutputStream(file);
        printStream = new PrintStream(fileOutputStream);
        printStream.println("Transaction Hash,Gas Used By Trx.,Actual Profit Generated,Threshold Eth Amount,Borrow Amount,Expected Profit (Eth)\n");
    }

    protected static void buildGraphQLQuery(int index, boolean setQueryString, TheGraphQueryMaker theGraphQueryMaker, HashMap<String, PairData> hashMap,
                                            String[][] currentNetworkPairIds, TokenIdToPairIdMapper tokenIdToPairIdMapper) {
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
            if (setQueryString) {
                stringBuilder.append("\"").append(currentNetworkPairIds[j][0]).append("\"");
                if (j < len - 1) {
                    stringBuilder.append(", ");
                }
            }
        }

        if (setQueryString) {
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

        web3j = Web3j.build(new HttpService(web3EndpointUrl));

        try {
            MainClass.logPrintStream.println("Web3 Client Version : " + web3j.web3ClientVersion().send().getWeb3ClientVersion());
            long chainId = web3j.ethChainId().send().getChainId().longValue();
            rawTransactionManager = new RawTransactionManager(web3j, credentials, chainId);
        } catch (IOException e) {
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
                boolean shutdownStatus = coreSystemExecutorService.awaitTermination(4, TimeUnit.SECONDS);
                if (!shutdownStatus) {
                    MainClass.logPrintStream.println("Unable to shutdown within 4 seconds");
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

    // Tell if (pending nonce == completed nonce) then Get/Set Gas prices
    private Object[] web3BatchCalls() {
        Object[] retVal = new Object[]{
                false,
                null
        };

        try {
            BatchRequest batchRequest = web3j.newBatch();
            batchRequest.add(web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.LATEST))
                    .add(web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING))
                    .add(web3j.ethGasPrice());
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
            object = responses.get(2);
            if (object instanceof EthGasPrice) {
                gasPrice = ((EthGasPrice) object).getGasPrice();
                maxGasFees = new BigDecimal(gasLimit.multiply(gasPrice)).divide(new BigDecimal("1000000000000000000"), mathContextUp);
            }
            thresholdEthAmount = maxGasFees.multiply(thresholdLevel);

            if (noncePending != null && nonceComplete != null && noncePending.compareTo(nonceComplete) == 0) {
                pendingCount = 0;
                retVal[0] = true;
                retVal[1] = noncePending;
            }
        } catch (Exception e) {
            e.printStackTrace(MainClass.logPrintStream);
        }

        return retVal;
    }

    private static void makeQueriesAndSetData(ArrayList<TheGraphQueryMaker> allQueryMakers,
                                              HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData) {
        for (TheGraphQueryMaker theGraphQueryMaker : allQueryMakers) {

            JSONObject jsonObject = theGraphQueryMaker.sendQuery();

            if (jsonObject != null) {

                JSONArray allPairs = jsonObject.getJSONArray("pairs");
                PairData pairData;

                for (int i = 0; i < allPairs.length(); i++) {
                    jsonObject = allPairs.getJSONObject(i);
                    pairData = allNetworkAllPairData.get(theGraphQueryMaker).get(jsonObject.getString("id"));
                    if (pairData != null) {
                        try {
                            pairData.setTokenVolumesAndDerivedETH(
                                    jsonObject.getString("reserve0"), jsonObject.getString("reserve1"),
                                    jsonObject.getJSONObject("token0").getString("derivedETH"),
                                    jsonObject.getJSONObject("token1").getString("derivedETH"));
                        } catch (Exception e) {
                            MainClass.logPrintStream.println("Error for : " + jsonObject);
                            e.printStackTrace(MainClass.logPrintStream);
                        }
                    }
                }
            }
        }
    }

    private void analizeAllPairsAndPerformArbitrage(boolean shouldSendTransactions) {

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

            executorCompletionService.submit(new PairAnalizer(borrowMode, allPairIdsForSpecificPair, allNetworkAllPairData, allQueryMakers,
                    MainClass.logPrintStream));
            jobCount++;
        }

        didPerformArbitrage = false;
        for (int i = 0; i < jobCount; i++) {
            try {
                AnalizedPairData analizedPairData = executorCompletionService.take().get();
                if (analizedPairData != null) {
                    if (shouldSendTransactions && (pendingCount < maxPendingTrxAllowed) &&
                            (analizedPairData.maxProfitInETH.compareTo(thresholdEthAmount) > 0)) {

                        // Perform Arbitrage if condition checks are satisfied....
                        if (performArbitrage(analizedPairData)) {
                            int newCount = pendingCount;
                            pendingCount = newCount + 1;
                        }
                    }

                    allAnalizedPairData.add(analizedPairData);
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
        }

        pairAnalysingExecutorService.shutdownNow();
        Collections.sort(allAnalizedPairData);
        Collections.reverse(allAnalizedPairData);
    }

    private boolean performArbitrage(AnalizedPairData analizedPairData) {
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

        try {
            EthCall ethCall = web3j.ethCall(Transaction.createEthCallTransaction(credentials.getAddress(), arbitrageContractAddress, encodedFunction),
                    DefaultBlockParameterName.PENDING).send();
            if (ethCall.isReverted()) {
                throw new IOException("Reverted... Function : " + encodedFunction + "\nReason : " + ethCall.getRevertReason());
            } else {
                MainClass.logPrintStream.println("Eth Call Success. Value : " + ethCall.getValue());
            }
            RawTransaction rawTransaction = RawTransaction.createTransaction(
                    nonce,
                    gasPrice,
                    gasLimit,
                    arbitrageContractAddress,
                    BigInteger.ZERO,
                    encodedFunction
            );
            String trxHash = rawTransactionManager.signAndSend(rawTransaction).getTransactionHash();
            sentTransactionHashAndData.put(trxHash, new ExecutedTransactionData(
                    trxHash, encodedFunction, gasPrice, analizedPairData.repayTokenSymbol,
                    Integer.parseInt(analizedPairData.repayTokenDecimals), thresholdEthAmount,
                    analizedPairData.maxBorrowAmount, analizedPairData.maxProfitInETH));

            BigInteger newNonce = nonce;
            this.nonce = newNonce.add(BigInteger.ONE);
            didPerformArbitrage = true;
            return true;
        } catch (IOException e) {
            e.printStackTrace(MainClass.logPrintStream);
            return false;
        }
    }

    protected static void printAllDeterminedData(ArrayList<TheGraphQueryMaker> allQueryMakers,
                                                 HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData,
                                                 ArrayList<AnalizedPairData> allAnalizedPairData, PrintStream... printStreams) {
        for (PrintStream printStream : printStreams) {
            printStream.println("""
                    <-----     Printing All Determined Data     ----->
                                        
                                        
                                        
                    Pair Id,Token 0 Symbol,Token 1 Symbol,Token 0 Volume,Token 1 Volume,Token 0 StaticPrice,Token 1 StaticPrice,Last Update Time,Token 0 Id,Token 1 Id,Exchange No.,Network Name
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

            printStream.printf("""
                                        
                                        
                    Notes: -
                    Borrow Token means the token we borrow from Exchange A
                    and sell on Exchange B. Repay Token means the token we
                    repay to Exchange A that we get from Exchange B.
                    Max. Profit is in terms of repay token.
                                        
                    Threshold ETH : %s
                                        
                                        
                    <-----     Data Printing Complete     ----->%n""", thresholdEthAmount.toString());
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
            printAllDeterminedData(allQueryMakers, allNetworkAllPairData, allAnalizedPairData, printStream);
            printStream.close();
            fileOutputStream.close();
            arbitrageTelegramBot.sendFile("GatheredData.csv", chatId);
            return true;
        } catch (IOException e) {
            e.printStackTrace(MainClass.logPrintStream);
            return false;
        }
    }

    public void getPrintedAnalysisData(boolean shouldSentNotifier, String... chatId) {
        if (hasPrintedAnything) {
            printStream.flush();
            arbitrageTelegramBot.sendFile(chainName + "-ArbitrageResults.csv", chatId);
        } else if (shouldSentNotifier) {
            for (String id : chatId) {
                arbitrageTelegramBot.sendMessage(id, "No arbitrage was performed in last 24 Hrs.");
            }
        }
    }

    public void startInnerMiniEnvironment(String chatId, String[] dexTheGraphHostUrls, String[][][] allPairIdsOnAllNetworks) {
        InterEnvironmentMiniSystem interEnvironmentMiniSystem = new InterEnvironmentMiniSystem(chatId, dexTheGraphHostUrls, allPairIdsOnAllNetworks);
        interEnvironmentMiniSystem.run();
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
                Object[] web3CallResults = web3BatchCalls();
                boolean shouldArbitrage = (boolean) web3CallResults[0];
                nonce = (BigInteger) web3CallResults[1];
                long startTime = System.nanoTime();
                makeQueriesAndSetData(allQueryMakers, allNetworkAllPairData);
                long queryTime = System.nanoTime();
                analizeAllPairsAndPerformArbitrage(shouldArbitrage);
                long analysisTime = System.nanoTime();

                if (didPerformArbitrage) {
                    MainClass.logPrintStream.println("Time for Querying : " + (queryTime - startTime) +
                            "\nTime for Analysis & Arbitrage : " + (analysisTime - queryTime));
                    printAllDeterminedData(allQueryMakers, allNetworkAllPairData, allAnalizedPairData, MainClass.logPrintStream);
                }
                if (!(sentTransactionHashAndData.isEmpty() || isPrintingAnalysis || analysisPrinter.isShutdown())) {
                    analysisPrinter.submit(new AnalysisPrinter());
                }
            } finally {
                mutex.release();
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

    private class InterEnvironmentMiniSystem implements Runnable {

        private final ArrayList<String> allExchangesToMonitor = new ArrayList<>();
        private final ArrayList<TheGraphQueryMaker> allQueryMakers = new ArrayList<>();
        private final HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData = new HashMap<>();
        private final ArrayList<AnalizedPairData> allAnalizedPairData = new ArrayList<>();
        private final TokenIdToPairIdMapper tokenIdToPairIdMapper = new TokenIdToPairIdMapper();
        private final String chatId;

        InterEnvironmentMiniSystem(String chatId, String[] dexTheGraphHostUrls, String[][][] allPairIdsOnAllNetworks) {
            this.chatId = chatId;
            allExchangesToMonitor.addAll(Arrays.asList(dexTheGraphHostUrls));
            int length = dexTheGraphHostUrls.length;
            for (int i = 0; i < length; i++) {
                TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker(dexTheGraphHostUrls[i], MainClass.logPrintStream);
                allQueryMakers.add(theGraphQueryMaker);
                HashMap<String, PairData> hashMap = new HashMap<>();
                allNetworkAllPairData.put(theGraphQueryMaker, hashMap);

                buildGraphQLQuery(i, false, theGraphQueryMaker, hashMap, allPairIdsOnAllNetworks[i], tokenIdToPairIdMapper);
            }
        }

        @Override
        public void run() {
            String[] allKeys = tokenIdToPairIdMapper.keySet().toArray(String[]::new);
            StringBuilder[] queryBuilders = new StringBuilder[allExchangesToMonitor.size()];
            for (int start = 0; start < allKeys.length; ) {
                // Build Query Strings Start
                int end = start + 24;
                for (int i = 0; i < queryBuilders.length; i++) {
                    queryBuilders[i] = new StringBuilder("[");
                }
                for (; start <= end; start++)
                    if (start < allKeys.length) {
                        ArrayList<String> currentPairAllIds = tokenIdToPairIdMapper.get(allKeys[start]);
                        for (int i = 0; i < queryBuilders.length; i++) {
                            try {
                                if (!currentPairAllIds.get(i).equalsIgnoreCase("")) {
                                    queryBuilders[i].append("\"")
                                            .append(currentPairAllIds.get(i))
                                            .append("\"");
                                    if (start != end) {
                                        queryBuilders[i].append(", ");
                                    }
                                }
                            } catch (IndexOutOfBoundsException e) {
                                // Ignore...
                            }
                        }
                    }
                for (StringBuilder queryBuilder : queryBuilders) {
                    queryBuilder.append("]");
                }
                for (int i = 0; i < queryBuilders.length; i++) {
                    allQueryMakers.get(i).setGraphQLQuery(String.format("""
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
                            }""", queryBuilders[i]));
                }
                // Build Query String complete

                makeQueriesAndSetData(allQueryMakers, allNetworkAllPairData);
                ArrayList<String> currentKeys = new ArrayList<>(Arrays.asList(allKeys).subList(end - 24, Math.min(end + 1, allKeys.length)));
                analizeAllPairsAndPerformArbitrage(currentKeys, pendingCount == 0);
            }


            arbitrageTelegramBot.sendMessage(chatId, "Universal List Rotation Complete. Please see arbitrage " +
                    "results to know if any arbitrage was performed...");
            Collections.sort(allAnalizedPairData);
            Collections.reverse(allAnalizedPairData);
            if (!printAllDeterminedData(chatId)) {
                arbitrageTelegramBot.sendMessage(chatId, "There was an error when sending the all pair data...");
            }
        }

        private void analizeAllPairsAndPerformArbitrage(ArrayList<String> keys, boolean shouldSendTransactions) {
            ExecutorService pairAnalysingExecutorService = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
            ExecutorCompletionService<AnalizedPairData> executorCompletionService = new ExecutorCompletionService<>(pairAnalysingExecutorService);
            int jobCount = 0;
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

                executorCompletionService.submit(new PairAnalizer(borrowMode, allPairIdsForSpecificPair, allNetworkAllPairData, allQueryMakers,
                        MainClass.logPrintStream));
                jobCount++;
            }

            for (int i = 0; i < jobCount; i++) {
                try {
                    AnalizedPairData analizedPairData = executorCompletionService.take().get();
                    if (analizedPairData != null) {
                        if (shouldSendTransactions && (pendingCount < maxPendingTrxAllowed) &&
                                (analizedPairData.maxProfitInETH.compareTo(thresholdEthAmount) > 0)) {

                            // Perform Arbitrage if condition checks are satisfied....
                            if (performArbitrage(analizedPairData)) {
                                int newCount = pendingCount;
                                pendingCount = newCount + 1;
                            }
                        }

                        allAnalizedPairData.add(analizedPairData);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace(MainClass.logPrintStream);
                }
            }

            pairAnalysingExecutorService.shutdownNow();
        }

        private boolean printAllDeterminedData(String chatId) {
            try {
                File file = new File("UNIVERSAL-GatheredData.csv");
                if (!file.exists()) {
                    if (!file.createNewFile()) {
                        return false;
                    }
                }
                FileOutputStream fileOutputStream = new FileOutputStream("UNIVERSAL-GatheredData.csv");
                PrintStream printStream = new PrintStream(fileOutputStream);
                ArbitrageSystem.printAllDeterminedData(allQueryMakers, allNetworkAllPairData, allAnalizedPairData, printStream);
                printStream.close();
                fileOutputStream.close();
                arbitrageTelegramBot.sendFile("UNIVERSAL-GatheredData.csv", chatId);
                return true;
            } catch (IOException e) {
                e.printStackTrace(MainClass.logPrintStream);
                return false;
            }
        }
    }
}
