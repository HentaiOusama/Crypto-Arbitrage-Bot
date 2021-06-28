import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

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

    // Web3 Related Variables
    private Web3j web3j;

    // Crypto Related Variables
    private final ArrayList<String> allTokenAddressesToMonitor = new ArrayList<>();
    private final ArrayList<String> allExchangesToMonitor = new ArrayList<>();
    private final String arbitrageContractAddress;
    private final ArrayList<TheGraphQueryMaker> allQueryMakers = new ArrayList<>();
    // Mapping TheGraphQueryMaker to a mapping of pairId mapped to PairData 👇
    private final HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData = new HashMap<>();
    private final TokenIdToPairIdMapper tokenIdToPairIdMapper = new TokenIdToPairIdMapper();
    private final ArrayList<AnalysedPairData> allAnalysedPairData = new ArrayList<>();


    ArbitrageSystem(String arbitrageContractAddress, int waitTimeInMillis, float thresholdPriceDifferencePercentage,
                    String[] dexTheGraphHostUrls, String[][][] allPairIdsOnAllNetworks) {
        assert dexTheGraphHostUrls.length == allPairIdsOnAllNetworks.length;
        this.arbitrageContractAddress = arbitrageContractAddress;
        this.waitTimeInMillis = waitTimeInMillis;
        this.thresholdPriceDifferencePercentage = thresholdPriceDifferencePercentage;

        int length = dexTheGraphHostUrls.length;
        for (int i = 0; i < length; i++) {
            TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker(dexTheGraphHostUrls[i]);
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

    public void printAllDeterminedData(PrintStream... printStream) {
        for (PrintStream currentPrintStream : printStream) {
            currentPrintStream.println("------------------------------------------");
            currentPrintStream.println("----- Printing all determined Data \uD83D\uDC47 -----");
            currentPrintStream.println("------------------------------------------");
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

    @Override
    public void run() {
        MainClass.logPrintStream.println("Arbitrage System Running Now...");
        System.out.println("Arbitrage System Running Now...");

        while (shouldRunArbitrageSystem) {
            try {
                Thread.sleep(waitTimeInMillis);
            } catch (Exception e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
            //--------------------------------------------------//

            makeQueriesAndSetData();
            analyseAllPairsForArbitragePossibility(thresholdPriceDifferencePercentage);
            printAllDeterminedData(System.out, MainClass.logPrintStream);
        }

        shutdownSystem();
        MainClass.logPrintStream.println("Arbitrage System Stopped Running...");
        System.out.println("Arbitrage System Stopped Running...");
    }
}
