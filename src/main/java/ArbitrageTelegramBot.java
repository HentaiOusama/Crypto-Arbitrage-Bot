import SupportingClasses.NetworkData;
import SupportingClasses.TheGraphQueryMaker;
import SupportingClasses.TwoDexPairData;
import com.google.common.primitives.Booleans;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.web3j.crypto.Credentials;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;

/*
 * Requirements: -
 * 1) Environment Var: mongoID - MongoDB ID
 * 2) Environment Var: mongoPass - MongoDB Password
 * 3) Environment Var: ArbitrageBotToken - Telegram Bot Token
 * 4) Environment Var: HttpsEndpoint - Https Url from node hosting endpoint services such as Infura, QuickNode, etc
 * */
public class ArbitrageTelegramBot extends TelegramLongPollingBot {

    // Manager Variables
    private final String botToken = System.getenv("ArbitrageBotToken");
    private final boolean[] shouldRunSystems;
    private final ArrayList<String> allAdmins = new ArrayList<>();
    private int thresholdLevel;
    private int pollingInterval, maxPendingTrxAllowed; // Milliseconds, int

    // MongoDB Variables
    public MongoClient mongoClient;
    private MongoCollection<Document> allPairAndTrackersDataCollection;

    // Tracker and Pair Data
    private final ArrayList<String> allChainsNetworkId = new ArrayList<>();
    private final ArrayList<String> allChainsNetworkEndpointUrls = new ArrayList<>();
    private final HashMap<String, NetworkData> allChainsNetworkData = new HashMap<>();
    private final HashMap<String, ArbitrageSystem> runningArbitrageSystems = new HashMap<>();
    private String walletPrivateKey;

    ArbitrageTelegramBot() {

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                super.run();
                System.out.println("Shutdown Handler Called...");
                try {
                    if (mongoClient != null) {
                        mongoClient.close();
                    }
                    // TODO : Change it to multi Thread...
                    for (ArbitrageSystem arbitrageSystem : runningArbitrageSystems.values()) {
                        arbitrageSystem.stopSystem(allAdmins.toArray(String[]::new));
                    }
                    MainClass.logPrintStream.println("Shutdown Handler Called...");
                    sendLogs(allAdmins.get(0));
                    MainClass.logPrintStream.close();
                    MainClass.fileOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                System.out.println("Shutdown Successful...");
            }
        });

        shouldRunSystems = initializeMongoSetup();

        for (int i = 0; i < allChainsNetworkId.size(); i++) {
            if (shouldRunSystems[i] && !startArbitrageSystem(i)) {
                sendMessage(allAdmins.get(0), "Error occurred when trying to start the bot[" + i + "]. Fatal Error. Check it immediately");
            }
        }
    }

    @SuppressWarnings("SpellCheckingInspection")
    private boolean[] initializeMongoSetup() {
        ConnectionString connectionString = new ConnectionString(
                "mongodb+srv://" + System.getenv("mongoID") + ":" +
                        System.getenv("mongoPass") + "@hellgatesbotcluster.zm0r5.mongodb.net/test" +
                        "?keepAlive=true&poolSize=30&autoReconnect=true&socketTimeoutMS=360000&connectTimeoutMS=360000"
        );
        MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString).retryWrites(true).writeConcern(WriteConcern.MAJORITY).build();
        mongoClient = MongoClients.create(mongoClientSettings);
        // MongoDB Related Stuff
        mongoClient.startSession();
        allPairAndTrackersDataCollection = mongoClient.getDatabase("Arbitrage-Bot-Database").getCollection("All-Pairs-And-Trackers-Data");

        // General variables initialization
        Document document = new Document("identifier", "general");
        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
        assert foundDoc != null;

        thresholdLevel = (int) foundDoc.get("thresholdLevel");
        walletPrivateKey = (String) foundDoc.get("walletPrivateKey");
        maxPendingTrxAllowed = (int) foundDoc.get("maxPendingTrxAllowed");
        pollingInterval = (int) foundDoc.get("pollingInterval");
        List<?> list = (List<?>) foundDoc.get("admins");
        for (Object item : list) {
            if (item instanceof String) {
                allAdmins.add((String) item);
            }
        }
        list = (List<?>) foundDoc.get("networkNames");
        for (Object item : list) {
            if (item instanceof String) {
                allChainsNetworkId.add((String) item);
            }
        }
        list = (List<?>) foundDoc.get("networkEndpoints");
        for (Object item : list) {
            if (item instanceof String) {
                allChainsNetworkEndpointUrls.add((String) item);
            }
        }
        ArrayList<Boolean> shouldRunBots = new ArrayList<>();
        list = (List<?>) foundDoc.get("shouldRunBots");
        for (Object item : list) {
            if (item instanceof Boolean) {
                shouldRunBots.add((boolean) item);
            }
        }
        MainClass.logPrintStream.println("Admins : \n" + allAdmins + "\n");
        MainClass.logPrintStream.println("Networks : \n" + allChainsNetworkId + "\n\n");

        return Booleans.toArray(shouldRunBots);
    }

    private void getInitializingDataFromMongoDB(int networkIndex) throws IOException {
        String chainNetworkId = allChainsNetworkId.get(networkIndex);
        allChainsNetworkData.remove(chainNetworkId);
        Document document = new Document("identifier", "root");
        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
        assert foundDoc != null;

        String[] allTrackerUrls;
        String[][][] allPairIdsAndTokenDetails = null; // Url -> [ pairId -> {paidID, token0Id, token1Id, token0Symbol, token1Symbol, decimal0, decimal1} ]
        String arbitrageContractAddress = (String) foundDoc.get(chainNetworkId + "-arbitrageContractAddress");

        List<?> list1 = (List<?>) foundDoc.get(chainNetworkId + "-TrackerUrls");
        int len1 = list1.size();
        MainClass.logPrintStream.println(chainNetworkId + " : All Tracker Urls Size : " + len1);
        allTrackerUrls = new String[len1];

        for (int i = 0; i < len1; i++) {
            Object item1 = list1.get(i);
            if (item1 instanceof String) {
                String currentUrl = (String) item1;
                allTrackerUrls[i] = currentUrl;

                document = new Document("trackerId", currentUrl);
                foundDoc = allPairAndTrackersDataCollection.find(document).first();
                assert foundDoc != null;
                List<?> list2 = (List<?>) foundDoc.get("allPairIds");
                if (i == 0) {
                    allPairIdsAndTokenDetails = new String[len1][list2.size()][8];
                }

                int len2 = list2.size();
                for (int j = 0; j < len2; j++) {
                    Object item2 = list2.get(j);
                    if (item2 instanceof String) {
                        String currentPairId = (String) item2;
                        List<?> list3 = (List<?>) foundDoc.get(currentPairId);

                        int len3 = list3.size();
                        if (allPairIdsAndTokenDetails == null) {
                            throw new IOException("Database Error... (ID : 1). Please contact dev.");
                        }
                        allPairIdsAndTokenDetails[i][j][0] = currentPairId;
                        allPairIdsAndTokenDetails[i][j][7] = (String) foundDoc.get("routerIndex");
                        for (int k = 0; k < len3; k++) {
                            Object item3 = list3.get(k);
                            if (item3 instanceof String) {
                                allPairIdsAndTokenDetails[i][j][k + 1] = (String) item3;
                            }
                        }
                    }
                }
            }
        }

        allChainsNetworkData.put(chainNetworkId, new NetworkData(chainNetworkId, allChainsNetworkEndpointUrls.get(networkIndex), arbitrageContractAddress,
                allTrackerUrls, allPairIdsAndTokenDetails));
    }

    public boolean startArbitrageSystem(int networkIndex) {
        String chainNetworkId = allChainsNetworkId.get(networkIndex);
        stopArbitrageSystem(networkIndex);

        try {
            getInitializingDataFromMongoDB(networkIndex);
            try {
                NetworkData networkData = allChainsNetworkData.get(chainNetworkId);
                ArbitrageSystem arbitrageSystem = new ArbitrageSystem(this, chainNetworkId, networkData.arbitrageContractAddress,
                        walletPrivateKey, pollingInterval, maxPendingTrxAllowed, BigDecimal.valueOf(thresholdLevel), networkData.allTrackerUrls,
                        networkData.allPairIdsAndTokenDetails, networkData.web3EndpointUrl);
                runningArbitrageSystems.put(chainNetworkId, arbitrageSystem);

                if (!shouldRunSystems[networkIndex]) {
                    shouldRunSystems[networkIndex] = true;
                    Document document = new Document("identifier", "general");
                    Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
                    assert foundDoc != null;
                    document = new Document("shouldRunBots", Booleans.asList(shouldRunSystems));
                    Bson updateOperation = new Document("$set", document);
                    allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);
                }

                MainClass.logPrintStream.println(chainNetworkId + " --> Call to Arbitrage Start System...");
                System.out.println(chainNetworkId + " --> Call to Arbitrage Start System...");
                arbitrageSystem.startSystem();
                networkData.tempList.clear();
            } catch (IllegalArgumentException | IOException e) {
                e.printStackTrace(MainClass.logPrintStream);
                stopArbitrageSystem(networkIndex);
                return false;
            }

            return true;
        } catch (IOException e) {
            e.printStackTrace(MainClass.logPrintStream);
            return false;
        }
    }

    public void stopArbitrageSystem(int networkIndex) {
        String chainNetworkId = allChainsNetworkId.get(networkIndex);

        if (runningArbitrageSystems.get(chainNetworkId) != null) {
            MainClass.logPrintStream.println("Call to Arbitrage Stop System Method");
            System.out.println("Call to Arbitrage Stop System Method");
            runningArbitrageSystems.remove(chainNetworkId).stopSystem(allAdmins.toArray(String[]::new));
        }

        if (shouldRunSystems[networkIndex]) {
            shouldRunSystems[networkIndex] = false;
            Document document = new Document("identifier", "general");
            Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
            assert foundDoc != null;
            document = new Document("shouldRunBots", Booleans.asList(shouldRunSystems));
            Bson updateAddyDocOperation = new Document("$set", document);
            allPairAndTrackersDataCollection.updateOne(foundDoc, updateAddyDocOperation);
        }
    }

    public void sendMessage(String chat_id, String msg, String... url) {
        if (url.length == 0) {
            SendMessage sendMessage = new SendMessage();
            sendMessage.setText(msg);
            sendMessage.setChatId(chat_id);
            try {
                execute(sendMessage);
            } catch (Exception e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
        } else {
            SendAnimation sendAnimation = new SendAnimation();
            sendAnimation.setAnimation(new InputFile().setMedia(url[(int) (Math.random() * (url.length))]));
            sendAnimation.setCaption(msg);
            sendAnimation.setChatId(chat_id);
            try {
                execute(sendAnimation);
            } catch (Exception e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
        }
    }

    public void sendFile(String fileName, String... chatId) {
        for (String id : chatId) {
            SendDocument sendDocument = new SendDocument();
            sendDocument.setChatId(id);
            sendDocument.setDocument(new InputFile().setMedia(new File(fileName)));
            sendDocument.setCaption(fileName);
            try {
                execute(sendDocument);
            } catch (Exception e) {
                e.printStackTrace(MainClass.logPrintStream);
            }
        }
    }

    private void sendLogs(String chatId) {
        MainClass.logPrintStream.flush();
        sendFile("OutputLogs.txt", chatId);
    }

    private void addNewPair(String chatId, String[] params) {
        if (params.length == 4) {
            try {
                params[1] = params[1].toUpperCase();
                int index = allChainsNetworkId.indexOf(params[1]);
                if (index == -1) {
                    sendMessage(chatId, "Invalid chainName");
                    return;
                }
                if (!shouldRunSystems[index]) {
                    sendMessage(chatId, "New pairs can only be added when the bot is running...");
                    return;
                }
                ArbitrageSystem arbitrageSystem = runningArbitrageSystems.get(params[1]);
                NetworkData networkData = allChainsNetworkData.get(params[1]);

                Set<String> oldPairs = arbitrageSystem.getAllUniSwapPairIds();
                ArrayList<String> result = arbitrageSystem.getPairDetails(params[2], params[3]);
                String token0Id = result.remove(0);
                String token1Id = result.remove(0);
                String token0Symbol = result.remove(0).toUpperCase();
                String token1Symbol = result.remove(0).toUpperCase();
                String decimal0 = result.remove(0);
                String decimal1 = result.remove(0);
                String msg = "Token0Id: " + token0Id + ", Token0Symbol: " + token0Symbol +
                        ", Token1Id: " + token1Id + ", Token1Symbol: " + token1Symbol +
                        ", Token0Decimals : " + decimal0 + ", Token1Decimals : " + decimal1 +
                        "\n\nAll PairIds:-\n" + result;

                String firstId = result.get(0);
                if (oldPairs.contains(firstId) || networkData.tempList.contains(firstId)) {
                    sendMessage(chatId, "This Pair Already Exist in the Monitoring List...");
                    return;
                }

                for (String host : networkData.allTrackerUrls) {
                    Document document = new Document("trackerId", host);
                    Document foundDoc = allPairAndTrackersDataCollection.find(document).first();

                    assert foundDoc != null;
                    if (foundDoc.get("allPairIds") instanceof List<?>) {
                        List<String> allPairIds = new ArrayList<>();
                        List<?> receivedPairIds = (List<?>) foundDoc.get("allPairIds");
                        for (Object item : receivedPairIds) {
                            if (item instanceof String) {
                                allPairIds.add((String) item);
                            }
                        }

                        String newPairId = result.remove(0);
                        if (!newPairId.equalsIgnoreCase("")) {
                            allPairIds.add(newPairId);

                            document = new Document("allPairIds", allPairIds);
                            if (!foundDoc.containsKey(newPairId)) {
                                List<String> newData = new ArrayList<>();
                                newData.add(token0Id);
                                newData.add(token1Id);
                                newData.add(token0Symbol);
                                newData.add(token1Symbol);
                                newData.add(decimal0);
                                newData.add(decimal1);
                                document.append(newPairId, newData);
                            }

                            Bson updateOperation = new Document("$set", document);
                            allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);
                            networkData.tempList.add(newPairId);
                        } else if (host.equals(networkData.allTrackerUrls[0])) {
                            sendMessage(chatId, "This pair was not found on Uniswap. Any pair that you want to add needs to exist on " +
                                    "at least Uniswap. Other swaps are optional.");
                            return;
                        }
                    }
                }

                sendMessage(chatId, "Operation Successful. Following data was stored in the database:- \n\n" +
                        msg + "\n\n(Once you have added all new Pairs, please restart the bot for changes to take effect.)");

            } catch (Exception e) {
                sendMessage(chatId, """
                        Invalid Token Ids. Make sure that: -
                        1) Both Token Ids are different
                        2) Both Token Ids are valid (I.e. they Exist)
                        3) Both have a pair on UniSwap""");
                e.printStackTrace(MainClass.logPrintStream);
            }
        } else {
            sendMessage(chatId, "Wrong Usage of Command. Correct format: -\n" +
                    "addNewPair   chainName   token0Address   token1Address");
        }
    }

    private void removeOldPair(String chatId, String[] params) {
        if (params.length == 4) {
            params[1] = params[1].toUpperCase();
            int index = allChainsNetworkId.indexOf(params[1]);
            if (index == -1) {
                sendMessage(chatId, "Invalid chainName");
                return;
            }
            if (!shouldRunSystems[index]) {
                sendMessage(chatId, "This command can only be used when the bot is running...");
                return;
            }
            ArbitrageSystem arbitrageSystem = runningArbitrageSystems.get(params[1]);
            NetworkData networkData = allChainsNetworkData.get(params[1]);

            String token0 = params[2].toLowerCase();
            String token1 = params[3].toLowerCase();

            if (token0.compareTo(token1) > 0) {
                String temp = token0;
                token0 = token1;
                token1 = temp;
            }

            ArrayList<String> pairIds = arbitrageSystem.removePair(token0, token1);
            if (pairIds != null) {
                ArrayList<String> removedPairs = new ArrayList<>();
                int length = pairIds.size();
                for (int i = 0; i < length; i++) {
                    String id = pairIds.get(i);
                    if (!id.equalsIgnoreCase("")) {
                        String host = networkData.allTrackerUrls[i];
                        Document document = new Document("trackerId", host);
                        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
                        Document newDocument = allPairAndTrackersDataCollection.find(document).first();
                        assert newDocument != null;
                        assert foundDoc != null;
                        List<?> list = (List<?>) foundDoc.get("allPairIds");
                        List<String> builtList = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof String) {
                                String foundId = (String) item;
                                if (!foundId.equalsIgnoreCase(id)) {
                                    builtList.add(foundId);
                                }
                            }
                        }

                        if (newDocument.remove(id) != null) {
                            removedPairs.add(id);
                        }
                        Bson updateOperation = new Document("$set", newDocument);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);

                        foundDoc = allPairAndTrackersDataCollection.find(document).first();
                        assert foundDoc != null;
                        document = new Document("allPairIds", builtList);
                        updateOperation = new Document("$set", document);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);
                    }
                }
                sendMessage(chatId, "Operation Successful...\nFollowing PairIds were removed from monitoring list: -\n" + removedPairs);
            } else {
                sendMessage(chatId, "No pair exist in with the given token addresses in the current monitoring list...");
            }
        } else {
            sendMessage(chatId, "Invalid Format. Correct Format: -\n" +
                    "removeOldPair   chainName   token0Addy   token1Addy");
        }
    }

    private void addNewTracker(String chatId, String[] params) throws IOException {
        params[1] = params[1].toUpperCase();
        if (params.length < 4) {
            sendMessage(chatId, "Wrong usage of command. Correct Format: -\n" +
                    "addNewTrackerUrl   chainName   theGraphUrlEndpoint   routerIndex");
            return;
        }
        if (!allChainsNetworkId.contains(params[1])) {
            sendMessage(chatId, "Invalid chainName");
            return;
        }
        NetworkData networkData = allChainsNetworkData.get(params[1]);

        if (networkData.allTrackerUrls == null) {
            MainClass.logPrintStream.println("First Time Data Fetch from MongoDB ????");
            getInitializingDataFromMongoDB(allChainsNetworkId.indexOf(params[1]));
        }

        try {
            Document document = new Document("trackerId", params[2]);
            Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
            if (foundDoc != null) {
                throw new Exception("Url Already Exists");
            }
            TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker(params[2], MainClass.logPrintStream);
            List<String> availablePairs = new ArrayList<>();

            try {
                if (networkData.allTrackerUrls == null) {
                    sendMessage(chatId, "Database Error... (ID : 2). Please contact dev.");
                    return;
                }
                Document document1 = new Document("trackerId", networkData.allTrackerUrls[0]);
                Document foundDoc1 = allPairAndTrackersDataCollection.find(document1).first();
                if (foundDoc1 == null) {
                    throw new Exception("Hmn... Uniswap Url Missing ??????");
                }

                List<?> allUniPairs = (List<?>) foundDoc1.get("allPairIds");
                for (Object item : allUniPairs) {
                    if (item instanceof String) {
                        String currentPairId = (String) item;
                        List<?> list = (List<?>) foundDoc1.get(currentPairId);
                        String[] tokenDetails = new String[list.size()];
                        int index = 0;

                        for (Object item1 : list) {
                            if (item1 instanceof String) {
                                tokenDetails[index] = (String) item1;
                                index++;
                            }
                        }

                        theGraphQueryMaker.setGraphQLQuery(String.format("""
                                {
                                    pairs(where: {token0: "%s", token1: "%s"}) {
                                        id
                                    }
                                }""", tokenDetails[0], tokenDetails[1]));

                        JSONObject jsonObject = theGraphQueryMaker.sendQuery();
                        if (jsonObject == null) {
                            throw new Exception("Invalid Url...");
                        }

                        JSONArray jsonArray = jsonObject.getJSONArray("pairs");
                        if (jsonArray.length() != 0) {
                            String id = jsonArray.getJSONObject(0).getString("id");
                            availablePairs.add(id);
                            document.append(id, Arrays.asList(tokenDetails));
                        }
                    }
                }
                document.append("allPairIds", availablePairs);
                document.append("routerIndex", params[3]);
                allPairAndTrackersDataCollection.insertOne(document);


                try {
                    document = new Document("identifier", "root");
                    foundDoc = allPairAndTrackersDataCollection.find(document).first();
                    assert foundDoc != null;

                    List<?> list = (List<?>) foundDoc.get(params[1] + "-TrackerUrls");
                    List<String> newList = new ArrayList<>();
                    int len1 = list.size();
                    networkData.allTrackerUrls = new String[len1];

                    for (Object item : list) {
                        if (item instanceof String) {
                            String currentUrl = (String) item;
                            newList.add(currentUrl);
                        }
                    }
                    newList.add(params[2]);

                    document = new Document(params[1] + "-TrackerUrls", newList);
                    Bson updateOperation = new Document("$set", document);
                    allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);

                    sendMessage(chatId, "Operation Successful.\n(Please restart the bot for changes to take effect.)");
                } catch (Exception e) {
                    sendMessage(chatId, "There was an error while updating the database for the given url.");
                    e.printStackTrace(MainClass.logPrintStream);
                }
            } catch (Exception e) {
                sendMessage(chatId, "Error while saving the new Tracker... Please check url, or see logs...");
                e.printStackTrace(MainClass.logPrintStream);
            }
        } catch (Exception e) {
            sendMessage(chatId, "Cannot add this url. This url is already present in the database");
            e.printStackTrace(MainClass.logPrintStream);
        }
    }

    private void buildUniversalList(String chatId, String[] params) {
        if (params.length == 4) {
            params[1] = params[1].toUpperCase();
            int parentIndex, childIndex;
            try {
                parentIndex = Integer.parseInt(params[2]);
                childIndex = Integer.parseInt(params[3]);
                if (childIndex < 0 || parentIndex <= 0 || parentIndex == childIndex) {
                    sendMessage(chatId, "Indices cannot be less than 0, parentIndex cannot be 0 and both indices cannot be same");
                    throw new NumberFormatException("Invalid Values");
                }
                if (!(childIndex == 0) && !(parentIndex == 1)) {
                    throw new NumberFormatException("Invalid Values of Indices");
                }
            } catch (NumberFormatException e) {
                e.printStackTrace(MainClass.logPrintStream);
                return;
            }

            if (allChainsNetworkId.contains(params[1])) {
                String parentHost, childHost;
                try {
                    if (!allChainsNetworkData.containsKey(params[1])) {
                        getInitializingDataFromMongoDB(allChainsNetworkId.indexOf(params[1]));
                    }
                    childHost = allChainsNetworkData.get(params[1]).allTrackerUrls[0];
                    parentHost = allChainsNetworkData.get(params[1]).allTrackerUrls[1];
                } catch (IOException e) {
                    e.printStackTrace(MainClass.logPrintStream);
                    sendMessage(chatId, "Database Error... (ID : 5). Contact Dev.");
                    return;
                } catch (ArrayIndexOutOfBoundsException e) {
                    e.printStackTrace(MainClass.logPrintStream);
                    sendMessage(chatId, "Indices values too large...");
                    return;
                }

                Document universalDoc = new Document("identifier", "universalList"),
                        parentDoc = new Document("trackerId", parentHost),
                        childDoc = new Document("trackerId", childHost);
                Document foundUniversalDoc = allPairAndTrackersDataCollection.find(universalDoc).first(),
                        foundParentDoc = allPairAndTrackersDataCollection.find(parentDoc).first(),
                        foundChildDoc = allPairAndTrackersDataCollection.find(childDoc).first();
                assert foundUniversalDoc != null && foundParentDoc != null && foundChildDoc != null;


                TheGraphQueryMaker sushiTheGraphQueryMaker = new TheGraphQueryMaker(parentHost, MainClass.logPrintStream);
                TheGraphQueryMaker uniTheGraphQueryMaker = new TheGraphQueryMaker(childHost, MainClass.logPrintStream);
                String pickKey = "uniswapFactories";
                sushiTheGraphQueryMaker.setGraphQLQuery("""
                        {
                            uniswapFactories(first: 1) {
                                pairCount
                            }
                        }""");
                JSONObject pairCountJsonObject = sushiTheGraphQueryMaker.sendQuery();
                if (pairCountJsonObject == null) {
                    sushiTheGraphQueryMaker.setGraphQLQuery("""
                            {
                                factories(first: 1) {
                                    pairCount
                                }
                            }""");
                    pickKey = "factories";
                    pairCountJsonObject = sushiTheGraphQueryMaker.sendQuery();
                }
                if (pairCountJsonObject == null) {
                    sendMessage(chatId, "Error while fetching data from subgraph...");
                    return;
                }
                int pairCount;
                try {
                    pairCount = (int) pairCountJsonObject.getJSONArray(pickKey).getJSONObject(0).get("pairCount");
                } catch (NumberFormatException e) {
                    e.printStackTrace(MainClass.logPrintStream);
                    sendMessage(chatId, "Subgraph returned invalid pairCount");
                    return;
                } catch (Exception e) {
                    e.printStackTrace(MainClass.logPrintStream);
                    sendMessage(chatId, "SubGraph Error...");
                    return;
                }


                ArrayList<String> allKeys = new ArrayList<>();
                HashMap<String, TwoDexPairData> twoDexPairDataHashMap = new HashMap<>();

                Document tempParentDoc = new Document(), tempChildDoc = new Document();
                boolean didUpdateParent = false, didUpdateChild = false;
                for (int skip = 0; skip < pairCount; skip += 100) {
                    sushiTheGraphQueryMaker.setGraphQLQuery(String.format("""
                            {
                                pairs(skip: %d, first: 100) {
                                    id
                                    token0 {
                                        id
                                        symbol
                                        decimals
                                    }
                                    token1 {
                                        id
                                        symbol
                                        decimals
                                    }
                                }
                            }""", skip));
                    JSONObject retrievedPairListJsonObject = sushiTheGraphQueryMaker.sendQuery();
                    if (retrievedPairListJsonObject == null || retrievedPairListJsonObject.isEmpty()) {
                        break;
                    }
                    JSONArray jsonArray = retrievedPairListJsonObject.getJSONArray("pairs");
                    String template = """
                            p%s : pairs(where: {token0 : "%s", token1: "%s"}) {
                               id
                            }""";
                    StringBuilder queryString = new StringBuilder();

                    ArrayList<String> keys = new ArrayList<>();
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject currentPairJsonObject = jsonArray.getJSONObject(i);
                        TwoDexPairData twoDexPairData = new TwoDexPairData();
                        twoDexPairData.parentPairId = currentPairJsonObject.getString("id");
                        twoDexPairData.parentDex = parentHost;
                        twoDexPairData.childDex = childHost;
                        twoDexPairData.token0 = currentPairJsonObject.getJSONObject("token0").getString("id");
                        twoDexPairData.token1 = currentPairJsonObject.getJSONObject("token1").getString("id");
                        twoDexPairData.decimal0 = currentPairJsonObject.getJSONObject("token0").getString("decimals");
                        twoDexPairData.decimal1 = currentPairJsonObject.getJSONObject("token1").getString("decimals");
                        twoDexPairData.symbol0 = currentPairJsonObject.getJSONObject("token0").getString("symbol");
                        twoDexPairData.symbol1 = currentPairJsonObject.getJSONObject("token1").getString("symbol");
                        String key = twoDexPairData.setAndGetKey();
                        keys.add(key);
                        twoDexPairDataHashMap.put(key, twoDexPairData);
                        queryString.append(String.format(template, i, twoDexPairData.token0, twoDexPairData.token1));
                        queryString.append("\n");
                    }

                    uniTheGraphQueryMaker.setGraphQLQuery(String.format("""
                            {
                                %s
                            }""", queryString));
                    pairCountJsonObject = uniTheGraphQueryMaker.sendQuery();
                    int internalIndex = 0;
                    for (int i = 0; i < keys.size(); i++) {
                        JSONArray jsonArray1 = pairCountJsonObject.getJSONArray("p" + internalIndex);
                        internalIndex++;
                        try {
                            String result;
                            if (jsonArray1.isEmpty()) {
                                twoDexPairDataHashMap.remove(keys.remove(i));
                                i--;
                            } else {
                                result = jsonArray1.getJSONObject(0).getString("id");
                                if (result == null || result.equalsIgnoreCase("")) {
                                    twoDexPairDataHashMap.remove(keys.remove(i));
                                    i--;
                                    throw new Exception("Unable to get pair from Uni");
                                }
                                twoDexPairDataHashMap.get(keys.get(i)).childPairId = result;
                            }
                        } catch (Exception e) {
                            e.printStackTrace(MainClass.logPrintStream);
                        }
                    }
                    allKeys.addAll(keys);

                    for (String key : keys) {
                        TwoDexPairData twoDexPairData = twoDexPairDataHashMap.get(key);
                        if (!twoDexPairData.isValid()) {
                            keys.remove(key);
                            continue;
                        }
                        ArrayList<String> interTempArrayList = new ArrayList<>();
                        interTempArrayList.add(twoDexPairData.token0);
                        interTempArrayList.add(twoDexPairData.token1);
                        interTempArrayList.add(twoDexPairData.symbol0);
                        interTempArrayList.add(twoDexPairData.symbol1);
                        interTempArrayList.add(twoDexPairData.decimal0);
                        interTempArrayList.add(twoDexPairData.decimal1);
                        if (!foundParentDoc.containsKey(twoDexPairData.parentPairId)) {
                            tempParentDoc.append(twoDexPairData.parentPairId, interTempArrayList);
                            didUpdateParent = true;
                        }
                        if (!foundChildDoc.containsKey(twoDexPairData.childPairId)) {
                            tempChildDoc.append(twoDexPairData.childPairId, interTempArrayList);
                            didUpdateChild = true;
                        }

                        List<?> list = (List<?>) foundUniversalDoc.get(key);
                        interTempArrayList = new ArrayList<>();
                        if (list == null) {
                            int len = allChainsNetworkData.get(params[1]).allTrackerUrls.length;
                            for (int i = 0; i < len; i++) {
                                interTempArrayList.add("");
                            }
                        } else {
                            for (Object o : list) {
                                if (o instanceof String) {
                                    interTempArrayList.add((String) o);
                                }
                            }
                        }

                        interTempArrayList.remove(parentIndex);
                        interTempArrayList.add(parentIndex, twoDexPairData.parentPairId);
                        interTempArrayList.remove(childIndex);
                        interTempArrayList.add(childIndex, twoDexPairData.childPairId);
                        universalDoc.append(twoDexPairData.key, interTempArrayList);
                    }
                }

                if (allKeys.size() > 0) {
                    universalDoc.append(params[1] + "-pairKeys", allKeys);
                    Bson updateOperation = new Document("$set", universalDoc);
                    allPairAndTrackersDataCollection.updateOne(foundUniversalDoc, updateOperation);

                    if (didUpdateParent) {
                        updateOperation = new Document("$set", tempParentDoc);
                        allPairAndTrackersDataCollection.updateOne(foundParentDoc, updateOperation);
                    }
                    if (didUpdateChild) {
                        updateOperation = new Document("$set", tempChildDoc);
                        allPairAndTrackersDataCollection.updateOne(foundChildDoc, updateOperation);
                    }
                    sendMessage(chatId, "Operation Successful...");
                } else {
                    sendMessage(chatId, "No pairs were fetched...");
                }
            } else {
                sendMessage(chatId, "Invalid chainName");
            }
        } else {
            sendMessage(chatId, "Wrong usage of command. Proper usage: -\n" +
                    "buildUniversalList   chainName   parentTrackerIndex(!0)   compareTrackerIndex");
        }
    }

    public void startRotationOnUniversalList(String chatId, String[] params) {
        if (params.length == 2) {
            params[1] = params[1].toUpperCase();
            if (!runningArbitrageSystems.containsKey(params[1])) {
                sendMessage(chatId, "This command can only be used when the arbitrage system for the input chainName is running");
                return;
            }

            Document universalDoc = new Document("identifier", "universalList"),
                    rootDoc = new Document("identifier", "root");
            Document foundUniversalDoc = allPairAndTrackersDataCollection.find(universalDoc).first(),
                    foundRootDoc = allPairAndTrackersDataCollection.find(rootDoc).first();
            assert foundUniversalDoc != null && foundRootDoc != null;

            Document[] trackerDocs;
            if (foundRootDoc.containsKey(params[1] + "-TrackerUrls")) {
                List<?> list = (List<?>) foundRootDoc.get(params[1] + "-TrackerUrls");
                trackerDocs = new Document[list.size()];
                String[] trackerList = new String[list.size()];
                int index = 0;
                for (Object o : list) {
                    if (o instanceof String) {
                        trackerList[index] = (String) o;
                        trackerDocs[index] = allPairAndTrackersDataCollection.find(new Document("trackerId", trackerList[index])).first();
                        assert trackerDocs[index] != null;
                        index++;
                    }
                }

                List<?> list1 = (List<?>) foundUniversalDoc.get(params[1] + "-pairKeys");
                String[][][] allData = new String[list.size()][list1.size()][8];

                for (int i = 0; i < list1.size(); i++) {
                    Object o = list1.get(i);
                    if (o instanceof String) {
                        String key = (String) o;
                        list = (List<?>) foundUniversalDoc.get(key);
                        index = 0;
                        for (Object obj : list) {
                            if (obj instanceof String && !(((String) obj).equalsIgnoreCase(""))) {
                                String pairId = (String) obj;
                                List<?> tempList = (List<?>) trackerDocs[index].get((pairId));
                                allData[index][i][0] = pairId;
                                allData[index][i][7] = (String) trackerDocs[index].get("routerIndex");
                                int innerIndex = 1;
                                for (Object iObj : tempList) {
                                    if (iObj instanceof String) {
                                        allData[index][i][innerIndex] = (String) iObj;
                                        innerIndex++;
                                    }
                                }
                                index++;
                            }
                        }
                    }
                }

                runningArbitrageSystems.get(params[1]).startInnerMiniEnvironment(chatId, trackerList, allData);
                sendMessage(chatId, "Operation Successful");
            } else {
                sendMessage(chatId, "Invalid chainName");
            }
        } else {
            sendMessage(chatId, "Invalid usage of command. Correct format: -\n" +
                    "startRotationOnUniversalList chainName");
        }
    }


    @Override
    public String getBotUsername() {
        return "RJ_Ethereum_Arbitrage_Bot";
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();
            String[] params = text.trim().split("[ ]+");
            MainClass.logPrintStream.println("From : " + chatId + "\nIncoming Message :\n" + text + "\n\n");

            // TODO : Remove it when BSC is ready
            if (params.length > 1) {
                params[1] = params[1].toUpperCase();
                if (params[1].equalsIgnoreCase("BSC")) {
                    sendMessage(chatId, "BSC System is not yet ready for use");
                    return;
                }
            }

            if (!allAdmins.contains(chatId)) {
                sendMessage(chatId, "This bot can only be used by authorized personnel. Sorry....");
                return;
            }

            if (params[0].equalsIgnoreCase("runSystem")) {
                if (params.length != 2) {
                    sendMessage(chatId, "Invalid format. Correct format: -\n" +
                            "runSystem   chainName");
                }
                int index = allChainsNetworkId.indexOf(params[1].toUpperCase());
                if (shouldRunSystems[index]) {
                    sendMessage(chatId, "The bot is already running...");
                } else {
                    try {
                        if (startArbitrageSystem(index)) {
                            sendMessage(chatId, "Operation Successful...");
                        } else {
                            throw new Exception("Unable to start the system");
                        }
                    } catch (Exception e) {
                        sendMessage(chatId, "There was an error while starting the bot... Please contact : @OreGaZembuTouchiSuru");
                        e.printStackTrace(MainClass.logPrintStream);
                    }
                }
            } else if (params[0].equalsIgnoreCase("stopSystem")) {
                if (params.length != 2) {
                    sendMessage(chatId, "Invalid format. Correct format: -\n" +
                            "stopSystem   chainName");
                }
                int index = allChainsNetworkId.indexOf(params[1].toUpperCase());
                if (!shouldRunSystems[index]) {
                    sendMessage(chatId, "The bot is already stopped...");
                } else {
                    stopArbitrageSystem(index);
                    sendMessage(chatId, "Operation Successful...");
                }
            } else if (params[0].equalsIgnoreCase("restartSystem")) {
                if (params.length != 2) {
                    sendMessage(chatId, "Invalid format. Correct format: -\n" +
                            "restartSystem   chainName");
                }
                int index = allChainsNetworkId.indexOf(params[1].toUpperCase());
                try {
                    if (startArbitrageSystem(index)) {
                        sendMessage(chatId, "Bot restarted.\n\nOperation Successful");
                    } else {
                        throw new Exception("Unable to start the bot");
                    }
                } catch (Exception e) {
                    sendMessage(chatId, "There was an error while turning on the bot...");
                }
            } else if (params[0].equalsIgnoreCase("setThresholdLevel")) {
                if (params.length == 2) {
                    try {
                        thresholdLevel = Integer.parseInt(params[1]);
                        if (thresholdLevel < 2) {
                            sendMessage(chatId, "Threshold Level cannot be less than 2");
                            return;
                        }
                        Document document = new Document("identifier", "general");
                        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
                        assert foundDoc != null;
                        document = new Document("thresholdLevel", thresholdLevel);
                        Bson updateOperation = new Document("$set", document);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);

                        for (int i = 0; i < allChainsNetworkId.size(); i++) {
                            try {
                                if (shouldRunSystems[i]) {
                                    runningArbitrageSystems.get(allChainsNetworkId.get(i)).thresholdLevel = BigDecimal.valueOf(thresholdLevel);
                                }
                            } catch (Exception e) {
                                e.printStackTrace(MainClass.logPrintStream);
                            }
                        }

                    } catch (NumberFormatException e) {
                        sendMessage(chatId, "Invalid Format... The levelNumber has to be an integer >= 2");
                    }
                } else {
                    sendMessage(chatId, "Wrong Usage of Command. Correct Format : \n" +
                            "setThresholdLevel   levelNumber");
                }
            } else if (params[0].equalsIgnoreCase("setPollingInterval")) {
                if (params.length == 2) {
                    Document document = new Document("identifier", "general");
                    Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
                    assert foundDoc != null;

                    try {
                        int interval = Integer.parseInt(params[1]);
                        if (interval < 10000) {
                            sendMessage(chatId, "timeInMillis has to be larger than 10000");
                            return;
                        }
                        document = new Document("pollingInterval", interval);
                        Bson updateOperation = new Document("$set", document);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);
                        pollingInterval = interval;
                        for (int i = 0; i < allChainsNetworkId.size(); i++) {
                            try {
                                if (shouldRunSystems[i]) {
                                    runningArbitrageSystems.get(allChainsNetworkId.get(i)).waitTimeInMillis = interval;
                                }
                            } catch (Exception e) {
                                e.printStackTrace(MainClass.logPrintStream);
                            }
                        }
                        sendMessage(chatId, "Operation Successful...");
                    } catch (NumberFormatException e) {
                        sendMessage(chatId, "Invalid time. timeInMillis has to be an Integer.");
                    }
                } else {
                    sendMessage(chatId, "Invalid Format. Correct Format: -\n" +
                            "setPollingInterval   timeInMillis");
                }
            } else if (params[0].equalsIgnoreCase("addNewPair")) {
                addNewPair(chatId, params);
            } else if (params[0].equalsIgnoreCase("removeOldPair")) {
                removeOldPair(chatId, params);
            } else if (params[0].equalsIgnoreCase("addNewTrackerUrl")) {
                try {
                    addNewTracker(chatId, params);
                } catch (IOException e) {
                    e.printStackTrace(MainClass.logPrintStream);
                    sendMessage(chatId, "Database Error... (ID : 3). Please contact dev.");
                }
            } else if (params[0].equalsIgnoreCase("getAllPairDetails")) {
                if (params.length != 2) {
                    sendMessage(chatId, "Invalid format. Correct format: -\n" +
                            "getAllPairDetails   chainName");
                } else {
                    params[1] = params[1].toUpperCase();
                    int index = allChainsNetworkId.indexOf(params[1]);
                    if (index != -1 && shouldRunSystems[index]) {
                        try {
                            if (!runningArbitrageSystems.get(params[1]).printAllDeterminedData(chatId)) {
                                sendMessage(chatId, "Error while generating data...");
                            }
                        } catch (Exception e) {
                            e.printStackTrace(MainClass.logPrintStream);
                            sendMessage(chatId, "Internal Error... (ID : 1). Please contract dev.");
                        }
                    } else {
                        sendMessage(chatId, ((index == -1) ? "Invalid chainName" : "This command can only be used when the bot is running..."));
                    }
                }
            } else if (params[0].equalsIgnoreCase("getLast24HrAnalysis")) {
                if (params.length != 2) {
                    sendMessage(chatId, "Invalid format. Correct format: -\n" +
                            "getLast24HrAnalysis   chainName");
                }
                params[1] = params[1].toUpperCase();
                int index = allChainsNetworkId.indexOf(params[1]);
                if (shouldRunSystems[index]) {
                    try {
                        runningArbitrageSystems.get(params[1]).getPrintedAnalysisData(true, chatId);
                    } catch (Exception e) {
                        e.printStackTrace(MainClass.logPrintStream);
                        sendMessage(chatId, "Internal Error... (ID : 2). Please contact dev.");
                    }
                } else {
                    sendMessage(chatId, "This command can only be used when the system is running...");
                }
            } else if (params[0].equalsIgnoreCase("setWalletPrivateKey")) {
                if (params.length == 2) {
                    try {
                        Credentials credentials = Credentials.create(params[1]);
                        Document document = new Document("identifier", "general");
                        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
                        assert foundDoc != null;

                        document = new Document("walletPrivateKey", params[1]);
                        Bson updateOperation = new Document("$set", document);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);

                        walletPrivateKey = params[1];
                        sendMessage(chatId, "Operation Successful.\n" +
                                "PrivateKey : " + params[1] + "\n\nWallet Address : " + credentials.getAddress() +
                                "\n\nPlease restart the bot for changes to take effect...");
                    } catch (NumberFormatException e) {
                        sendMessage(chatId, "This private key is invalid.");
                    }
                } else {
                    sendMessage(chatId, "Invalid format. Correct format :\n" +
                            "setWalletPrivateKey   privateKey");
                }
            } else if (params[0].equalsIgnoreCase("buildUniversalList")) {
                buildUniversalList(chatId, params);
            } else if (params[0].equalsIgnoreCase("startRotationOnUniversalList")) {
                startRotationOnUniversalList(chatId, params);
            } else if (params[0].equalsIgnoreCase("getLogs")) {
                sendLogs(chatId);
            } else if (params[0].equalsIgnoreCase("clearLogs")) {
                if (MainClass.logPrintStream != null) {
                    MainClass.logPrintStream.flush();
                }
                try {
                    MainClass.fileOutputStream = new FileOutputStream("OutputLogs.txt");
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
                MainClass.logPrintStream = new PrintStream(MainClass.fileOutputStream) {

                    @Override
                    public void println(@Nullable String x) {
                        super.println("----------------------------- (Open)");
                        super.println(x);
                        super.println("----------------------------- (Close)\n\n");
                    }

                    @Override
                    public void close() {
                        try {
                            MainClass.fileOutputStream.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        super.close();
                    }
                };
            } else if (params[0].equalsIgnoreCase("Commands")) {
                String message = """
                        01) runSystem   chainName
                                                
                        02) stopSystem   chainName
                                                
                        03) restartSystem   chainName
                                                
                        04) setThresholdLevel   levelNumber
                                                
                        05) addNewPair   chainName   token0Addy   token1Addy
                                                
                        06) removeOldPair   chainName   token0Addy   token1Addy
                                                
                        07) addNewTrackerUrl   chainName   theGraphUrl   routerIndex
                                                
                        08) getAllPairDetails   chainName
                                                
                        09) getLast24HrAnalysis   chainName
                                                
                        10) setWalletPrivateKey   privateKey
                                                
                        11) Commands%s
                                                
                                                
                        (For any command, 1st word is the actual command name and it may be followed by 0 or more parameters that must be replaced by the actual values.)""";

                if (chatId.equalsIgnoreCase(allAdmins.get(0))) {
                    message = String.format(message, """
                                                        
                                                        
                            ----------------ONLY DEV COMMANDS 👇----------------
                            01) setPollingInterval   timeInMillis   <- (requires restart)
                                                        
                            02) buildUniversalList   chainName   parentTrackerIndex(!0)   compareTrackerIndex
                                                        
                            03) startRotationOnUniversalList chainName
                                                        
                            03) getLogs
                                                
                            04) clearLogs""");
                } else {
                    message = String.format(message, "");
                }

                sendMessage(chatId, message);
            } else {
                sendMessage(chatId, "Such command does not exists. (ノಠ益ಠ)ノ彡┻━┻");
            }

            StringBuilder stringBuilder = new StringBuilder("Running Systems: -");
            for (int i = 0; i < allChainsNetworkId.size(); i++) {
                stringBuilder.append("\n")
                        .append(allChainsNetworkId.get(i))
                        .append(" : ")
                        .append(shouldRunSystems[i]);
            }
            stringBuilder.append("\n\n");
            stringBuilder.append("Threshold Level : ")
                    .append(thresholdLevel);
            sendMessage(chatId, stringBuilder.toString());
        }
    }
}
