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
        TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker("https://api.thegraph.com/subgraphs/name/uniswap/uniswap-v2");
        theGraphQueryMaker.setGraphQLQuery("""
                {
                    pairs(where :{token0: "0x1f6deadcb526c4710cf941872b86dcdfbbbd9211" }) {
                        id
                        token0{
                            id
                            symbol
                        }
                        token1 {
                            id
                            symbol
                        }
                    }
                }"""
        );
        JSONObject outputJSON = theGraphQueryMaker.sendQuery();
        if (outputJSON != null) {
            MainClass.logPrintStream.println(outputJSON);
        }

//        buildWeb3j();
//
//        while (shouldRunArbitrageSystem) {
//            // Do Something
//        }

        shutdownSystem();
    }
}
