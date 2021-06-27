import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.util.ArrayList;
import java.util.HashMap;

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

    // Web3 Related Variables
    private Web3j web3j;

    // Crypto Related Variables
    private final ArrayList<String> allTokenAddressesToMonitor = new ArrayList<>();
    private final ArrayList<String> allExchangesToMonitor = new ArrayList<>();
    private final String arbitrageContractAddress;
    private final ArrayList<TheGraphQueryMaker> allQueryMakers = new ArrayList<>();
    private final HashMap<TheGraphQueryMaker, HashMap<String, PairData>> allNetworkAllPairData = new HashMap<>();


    ArbitrageSystem(String arbitrageContractAddress, int waitTimeInMillis, String[] dexTheGraphHostUrls, String[][] allPairIdsOnAllNetworks) {
        assert dexTheGraphHostUrls.length == allPairIdsOnAllNetworks.length;
        this.arbitrageContractAddress = arbitrageContractAddress;
        this.waitTimeInMillis = waitTimeInMillis;

        int length = dexTheGraphHostUrls.length;
        for (int i = 0; i < length; i++) {
            TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker(dexTheGraphHostUrls[i]);
            allQueryMakers.add(theGraphQueryMaker);
            HashMap<String, PairData> hashMap = new HashMap<>();
            allNetworkAllPairData.put(theGraphQueryMaker, hashMap);


            String[] currentNetworkPairIds = allPairIdsOnAllNetworks[i];
            int len = currentNetworkPairIds.length;
            if (len == 0) {
                theGraphQueryMaker.isQueryMakerBad = true;
                continue;
            }
            StringBuilder stringBuilder = new StringBuilder("[");
            for (int j = 0; j < len; j++) {
                hashMap.put(currentNetworkPairIds[j], new PairData(currentNetworkPairIds[j], "", ""));
                stringBuilder.append("\"").append(currentNetworkPairIds[j]).append("\"");
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
                    }""", stringBuilder));
        }
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
                        pairData = new PairData(jsonObject.getString("id"), "", "");
                        allNetworkAllPairData.get(theGraphQueryMaker).put(jsonObject.getString("id"), pairData);
                    }
                    pairData.setToken0Volume(jsonObject.getString("reserve0"));
                    pairData.setToken1Volume(jsonObject.getString("reserve1"));
                }
            }
        }
    }

    public void printAllPairData() {
        for (TheGraphQueryMaker theGraphQueryMaker : allQueryMakers) {
            HashMap<String, PairData> currentNetworkPair = allNetworkAllPairData.get(theGraphQueryMaker);
            System.out.println("PairData from the network URL: " + theGraphQueryMaker.getHostUrl());
            System.out.println(currentNetworkPair);
        }
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
            printAllPairData();
        }

        shutdownSystem();
        MainClass.logPrintStream.println("Arbitrage System Stopped Running...");
        System.out.println("Arbitrage System Stopped Running...");
    }
}
