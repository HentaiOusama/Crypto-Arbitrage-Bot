import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.util.ArrayList;

/*
 * Requires Environment Variable :-
 * 1. HttpsEndpoint : Https Url from services such as Infura, QuickNode, etc*/
public class ArbitrageSystem implements Runnable {

    // Manager Variables
    private volatile boolean shouldRunArbitrageSystem = true;

    // Web3 Related Variables
    private Web3j web3j;

    // Crypto Related Variables
    private final ArrayList<String> allTokenAddressesToMonitor = new ArrayList<>();
    private final ArrayList<String> allExchangesToMonitor = new ArrayList<>();
    private final String arbitrageContractAddress;


    ArbitrageSystem(String arbitrageContractAddress) {
        this.arbitrageContractAddress = arbitrageContractAddress;
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

    @Override
    public void run() {
        TheGraphQueryMaker uniSwapQueryMaker = new TheGraphQueryMaker("https://api.thegraph.com/subgraphs/name/uniswap/uniswap-v2");
        TheGraphQueryMaker sushiSwapQueryMaker = new TheGraphQueryMaker("https://thegraph.com/explorer/subgraph/sushiswap/exchange");
        uniSwapQueryMaker.setGraphQLQuery("""
                {
                   pairs(where: {id_in: ["0x056bd5a0edee2bd5ba0b1a1671cf53aa22e03916"]}) {
                     id
                     token0 {
                       id
                     }
                     token1 {
                       id
                     }
                     reserve0
                     reserve1
                     token0Price
                     token1Price
                   }
                 }""");

        JSONObject queryOutput = uniSwapQueryMaker.sendQuery();
        if (queryOutput != null) {
            System.out.println(queryOutput);
            JSONArray jsonArray = queryOutput.getJSONArray("pairs");
            System.out.println("Array Size : " + jsonArray.length());
            System.out.println("Array Data : ");
            for (int i = 0; i < jsonArray.length(); i++) {
                System.out.println(jsonArray.get(i));
            }
        }

        shutdownSystem();
    }
}
