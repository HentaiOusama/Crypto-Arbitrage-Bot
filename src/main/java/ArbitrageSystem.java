import SupportingClasses.AnalysedPairData;
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
import java.util.*;

/*
 * Requires Environment Variable :-
 * 1. HttpsEndpoint : Https Url from node hosting endpoint services such as Infura, QuickNode, etc
 * -----------------------------------------------------------------------------------------
 *
 * System Assumptions: -
 * (When not explicitly mentioned, or understood, then the following indexes mean 👇)
 * 0 => UniSwap
 * 1 => SushiSwap
 * ------------------------------------------------------------------------------------------
 * */
public class ArbitrageSystem implements Runnable {

    // Manager Variables
    private volatile boolean shouldRunArbitrageSystem = true;
    private final int waitTimeInMillis;
    public BigDecimal thresholdPriceDifferencePercentage;
    private final ArbitrageTelegramBot arbitrageTelegramBot;

    // Web3 Related Variables
    private Web3j web3j;

    // Crypto Related Variables
    private final ArrayList<String> allExchangesToMonitor = new ArrayList<>();
    private final String arbitrageContractAddress;
    private final ArrayList<TheGraphQueryMaker> allQueryMakers = new ArrayList<>();
    // Mapping TheGraphQueryMaker to a mapping of pairId mapped to PairData 👇
    private final HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData = new HashMap<>();
    private final TokenIdToPairIdMapper tokenIdToPairIdMapper = new TokenIdToPairIdMapper();
    private final ArrayList<AnalysedPairData> allAnalysedPairData = new ArrayList<>();


    ArbitrageSystem(ArbitrageTelegramBot arbitrageTelegramBot, String arbitrageContractAddress, int waitTimeInMillis,
                    String thresholdPriceDifferencePercentage, String[] dexTheGraphHostUrls, String[][][] allPairIdsOnAllNetworks) {
        this.arbitrageTelegramBot = arbitrageTelegramBot;
        this.arbitrageContractAddress = arbitrageContractAddress;
        this.waitTimeInMillis = waitTimeInMillis;
        this.thresholdPriceDifferencePercentage = new BigDecimal(thresholdPriceDifferencePercentage);
        this.allExchangesToMonitor.addAll(Arrays.asList(dexTheGraphHostUrls));

        int length = dexTheGraphHostUrls.length;
        for (int i = 0; i < length; i++) {
            TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker(dexTheGraphHostUrls[i], MainClass.logPrintStream);
            allQueryMakers.add(theGraphQueryMaker);
            HashMap<String, PairData> hashMap = new HashMap<>();
            allNetworkAllPairData.put(theGraphQueryMaker, hashMap);

            buildGraphQLQuery(theGraphQueryMaker, hashMap, allPairIdsOnAllNetworks[i]);
        }
    }

    protected void buildGraphQLQuery(TheGraphQueryMaker theGraphQueryMaker, HashMap<String, PairData> hashMap, String[][] currentNetworkPairIds) {
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
            hashMap.put(currentNetworkPairIds[j][0], new PairData(currentNetworkPairIds[j][0], currentNetworkPairIds[j][1],
                    currentNetworkPairIds[j][2], currentNetworkPairIds[j][3], currentNetworkPairIds[j][4]
            ));
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
                        }
                        token1 {
                            symbol
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

    public void shutdownSystem() {
        if (web3j != null) {
            web3j.shutdown();
        }
    }

    public void buildWeb3j() {
        shutdownSystem();

        web3j = Web3j.build(new HttpService(System.getenv("HttpsEndpoint")));

        try {
            MainClass.logPrintStream.println("Web3 Client Version : " + web3j.web3ClientVersion().send().getWeb3ClientVersion());
        } catch (Exception e) {
            e.printStackTrace(MainClass.logPrintStream);
        }
    }

    public void stopSystem() {
        shouldRunArbitrageSystem = false;
    }

    protected void printAllDeterminedData(PrintStream... printStreams) {
        for (PrintStream printStream : printStreams) {
            printStream.println("|------------------------------------------|");
            printStream.println("|------ Printing All Determined Data  -----|");
            printStream.println("|------------------------------------------|\n\n\n");
            printStream.println("Pair Id,Token 0 Symbol,Token 1 Symbol,Token 0 Volume,Token 1 Volume,Token 0 StaticPrice,Token 1 Static" +
                    "Price,Last Update Time,Network Name\n");
        }

        for (TheGraphQueryMaker theGraphQueryMaker : allQueryMakers) {
            HashMap<String, PairData> currentNetworkPair = allNetworkAllPairData.get(theGraphQueryMaker);
            Set<String> keys = currentNetworkPair.keySet();

            StringBuilder allPairDataInCSVFormat = new StringBuilder();
            for (String key : keys) {
                allPairDataInCSVFormat
                        .append(currentNetworkPair.get(key))
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
                                
                                
                    |------------------------------------------|
                    |------ Trimmed Data After Analysis -------|
                    |------------------------------------------|
                    """);
            printStream.println("Token 0 Symbol,Token 1 Symbol,Min Price,Max Price,Price Difference,Price Difference (%)\n");

            for (AnalysedPairData analysedPairData : allAnalysedPairData) {
                printStream.println(analysedPairData);
            }

            printStream.println("\nThreshold Percentage Difference Used : " + thresholdPriceDifferencePercentage + " %");
            printStream.println("\n\n\n");
            printStream.println("|------------------------------------------|");
            printStream.println("|--------- Data Printing Complete  --------|");
            printStream.println("|------------------------------------------|");
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
            arbitrageTelegramBot.sendFile(chatId, "GatheredData.csv");
            printStream.close();
            fileOutputStream.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace(MainClass.logPrintStream);
            return false;
        }
    }

    public void makeQueriesAndSetData() {
        for (TheGraphQueryMaker theGraphQueryMaker : allQueryMakers) {

            JSONObject jsonObject = theGraphQueryMaker.sendQuery();

            if (jsonObject != null) {

                JSONArray allPairs = jsonObject.getJSONArray("pairs");
                PairData pairData;

                for (int i = 0; i < allPairs.length(); i++) {
                    jsonObject = allPairs.getJSONObject(i);
                    pairData = allNetworkAllPairData.get(theGraphQueryMaker).get(jsonObject.getString("id"));
                    if (pairData == null) {
                        MainClass.logPrintStream.println("Found Rouge Key. Creating New PairData");
                        pairData = new PairData(jsonObject.getString("id"), "", "", "", "");
                        allNetworkAllPairData.get(theGraphQueryMaker).put(jsonObject.getString("id"), pairData);
                    }
                    pairData.setTokenVolumes(jsonObject.getString("reserve0"), jsonObject.getString("reserve1"));
                }
            }
        }
    }

    public void analyseAllPairsForArbitragePossibility() {

        allAnalysedPairData.clear();
        Set<String> keys = tokenIdToPairIdMapper.keySet();
        for (String key : keys) {
            ArrayList<String> allPairIdsForSpecificPair = tokenIdToPairIdMapper.get(key);
            int len = allPairIdsForSpecificPair.size();
            if (len <= 0) {
                // Hopefully, we will never enter this block, but just in case.
                continue;
            }

            String token0 = allNetworkAllPairData.get(allQueryMakers.get(0)).get(allPairIdsForSpecificPair.get(0)).token0Symbol,
                    token1 = allNetworkAllPairData.get(allQueryMakers.get(0)).get(allPairIdsForSpecificPair.get(0)).token1Symbol;

            BigDecimal minPrice = allNetworkAllPairData.get(allQueryMakers.get(0)).get(allPairIdsForSpecificPair.get(0)).getToken0StaticPrice(),
                    maxPrice = allNetworkAllPairData.get(allQueryMakers.get(0)).get(allPairIdsForSpecificPair.get(0)).getToken0StaticPrice(),
                    currentPrice;
            int minIndex = 0, maxIndex = 0;

            for (int i = 0; i < len; i++) {
                currentPrice = allNetworkAllPairData.get(allQueryMakers.get(i)).get(allPairIdsForSpecificPair.get(i)).getToken0StaticPrice();
                if (currentPrice.compareTo(minPrice) < 0) {
                    minPrice = currentPrice;
                    minIndex = i;
                } else if (currentPrice.compareTo(maxPrice) > 0) {
                    maxPrice = currentPrice;
                    maxIndex = i;
                }
            }

            AnalysedPairData analysedPairData = new AnalysedPairData(key, token0, token1, minPrice, maxPrice, minIndex, maxIndex);
            if (thresholdPriceDifferencePercentage.compareTo(BigDecimal.valueOf(analysedPairData.priceDifferencePercentage)) <= 0) {
                allAnalysedPairData.add(analysedPairData);
            }
        }

        Collections.sort(allAnalysedPairData);
    }

    public Set<String> getAllUniSwapPairIds() {
        return allNetworkAllPairData.get(allQueryMakers.get(0)).keySet();
    }

    @Override
    public void run() {
        MainClass.logPrintStream.println("Arbitrage System Running Now...");
        System.out.println("Arbitrage System Running Now...");
        int count = 9;

        while (shouldRunArbitrageSystem) {
            try {
                Thread.sleep(waitTimeInMillis);
            } catch (Exception e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
            //--------------------------------------------------//

            makeQueriesAndSetData();
            analyseAllPairsForArbitragePossibility();
            if (count == 0) {
                printAllDeterminedData(MainClass.logPrintStream);
                count = 9;
            } else {
                count--;
            }
        }

        shutdownSystem();
        MainClass.logPrintStream.println("Arbitrage System Stopped Running...");
        System.out.println("Arbitrage System Stopped Running...");
    }
}
