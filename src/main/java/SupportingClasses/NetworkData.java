package SupportingClasses;

import java.util.ArrayList;

public class NetworkData {
    public final String networkId, web3EndpointUrl, arbitrageContractAddress;
    public String[] allTrackerUrls;
    public String[] derivedKeys;
    public final String[][][] allPairIdsAndTokenDetails; // Url -> [ pairId -> {paidID, token0Id, token1Id, token0Symbol, token1Symbol, decimal0, decimal1} ]
    public final ArrayList<String> tempList = new ArrayList<>();

    public NetworkData(String networkId, String web3EndpointUrl, String arbitrageContractAddress, String[] allTrackerUrls, String[] derivedKeys,
                       String[][][] allPairIdsAndTokenDetails) {
        this.networkId = networkId;
        this.web3EndpointUrl = web3EndpointUrl;
        this.arbitrageContractAddress = arbitrageContractAddress;
        this.allTrackerUrls = allTrackerUrls;
        this.derivedKeys = derivedKeys;
        this.allPairIdsAndTokenDetails = allPairIdsAndTokenDetails;
    }
}
