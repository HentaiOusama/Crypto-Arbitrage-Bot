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
    private final float thresholdPriceDifferencePercentage;
    private final ArbitrageTelegramBot arbitrageTelegramBot;

    // Web3 Related Variables
    private Web3j web3j;

    // Crypto Related Variables
    private final ArrayList<String> allExchangesToMonitor = new ArrayList<>();
    private final String arbitrageContractAddress;
    private final ArrayList<TheGraphQueryMaker> allQueryMakers = new ArrayList<>();
    // Mapping SupportingClasses.TheGraphQueryMaker to a mapping of pairId mapped to SupportingClasses.PairData 👇
    private final HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData = new HashMap<>();
    private final TokenIdToPairIdMapper tokenIdToPairIdMapper = new TokenIdToPairIdMapper();
    private final ArrayList<AnalysedPairData> allAnalysedPairData = new ArrayList<>();


    ArbitrageSystem(ArbitrageTelegramBot arbitrageTelegramBot, String arbitrageContractAddress, int waitTimeInMillis,
                    float thresholdPriceDifferencePercentage, String[] dexTheGraphHostUrls, String[][][] allPairIdsOnAllNetworks) {
        this.arbitrageTelegramBot = arbitrageTelegramBot;
        assert dexTheGraphHostUrls.length == allPairIdsOnAllNetworks.length;
        this.arbitrageContractAddress = arbitrageContractAddress;
        this.waitTimeInMillis = waitTimeInMillis;
        this.thresholdPriceDifferencePercentage = thresholdPriceDifferencePercentage;
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
            assert currentNetworkPairIds[j] != null;
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

    public ArrayList<String> getPairDetails(String token0, String token1) {
        assert !(token0.equalsIgnoreCase(token1));
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
        assert jsonObject != null;
        JSONArray jsonArray = jsonObject.getJSONArray("pairs");
        assert jsonArray.length() != 0;
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
            assert jsonObject != null;
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

    protected void printAllDeterminedData(PrintStream... printStream) {
        for (PrintStream currentPrintStream : printStream) {
            currentPrintStream.println("|------------------------------------------|");
            currentPrintStream.println("|----- Printing all determined Data \uD83D\uDC47 ----|");
            currentPrintStream.println("|------------------------------------------|");
        }

        for (TheGraphQueryMaker theGraphQueryMaker : allQueryMakers) {
            HashMap<String, PairData> currentNetworkPair = allNetworkAllPairData.get(theGraphQueryMaker);
            for (PrintStream currentPrintStream : printStream) {
                currentPrintStream.println("PairData from the network URL: " + theGraphQueryMaker.getHostUrl());
                currentPrintStream.println(currentNetworkPair);
                currentPrintStream.println("-----");
            }
        }

        for (PrintStream currentPrintStream : printStream) {
            currentPrintStream.println("------------------------------------------");
        }
        for (PrintStream currentPrintStream : printStream) {
            currentPrintStream.println(allAnalysedPairData);
        }
    }

    public boolean printAllDeterminedData(String chatId) {
        try {
            File file = new File("GatheredData.txt");
            if (!file.exists()) {
                if (!file.createNewFile()) {
                    return false;
                }
            }
            FileOutputStream fileOutputStream = new FileOutputStream("GatheredData.txt");
            PrintStream printStream = new PrintStream(fileOutputStream);
            printAllDeterminedData(printStream);
            arbitrageTelegramBot.sendFile(chatId, "GatheredData.txt");
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

    public void analyseAllPairsForArbitragePossibility(float thresholdPriceDifferencePercentage) {

        allAnalysedPairData.clear();
        Set<String> keys = tokenIdToPairIdMapper.keySet();
        for (String key : keys) {
            ArrayList<String> allPairIdsForSpecificPair = tokenIdToPairIdMapper.get(key);
            int len = allPairIdsForSpecificPair.size();
            if (len <= 0) {
                // Hopefully, we will never enter this block, but just in case.
                continue;
            }

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

            AnalysedPairData analysedPairData = new AnalysedPairData(key, minPrice, maxPrice, minIndex, maxIndex);
            if (analysedPairData.priceDifferencePercentage >= thresholdPriceDifferencePercentage) {
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
        int count = 0;

        while (shouldRunArbitrageSystem) {
            try {
                Thread.sleep(waitTimeInMillis);
            } catch (Exception e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
            //--------------------------------------------------//

            makeQueriesAndSetData();
            analyseAllPairsForArbitragePossibility(thresholdPriceDifferencePercentage);
            if (count == 9) {
                printAllDeterminedData(MainClass.logPrintStream);
                count = 0;
            } else {
                count++;
            }
        }

        shutdownSystem();
        MainClass.logPrintStream.println("Arbitrage System Stopped Running...");
        System.out.println("Arbitrage System Stopped Running...");
    }
}
