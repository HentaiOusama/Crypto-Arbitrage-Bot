import SupportingClasses.TheGraphQueryMaker;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/*
 * Requirements: -
 * 1) Environment Var: mongoID - MongoDB ID
 * 2) Environment Var: mongoPass - MongoDB Password
 * 3) Environment Var: ArbitrageBotToken - Telegram Bot Token
 * */
public class ArbitrageTelegramBot extends TelegramLongPollingBot {

    // Manager Variables
    private final String botToken = System.getenv("ArbitrageBotToken");
    private boolean shouldRunBot;
    private ArbitrageSystem arbitrageSystem;
    private final ArrayList<String> allAdmins = new ArrayList<>();
    private final ArrayList<String> tempList = new ArrayList<>();
    private String thresholdPercentage; // TODO : Not yet complete... Need to change this
    private int pollingInterval, maxPendingTrxAllowed; // Milliseconds, int

    public MongoClient mongoClient;
    private MongoCollection<Document> allPairAndTrackersDataCollection;

    // Tracker and Pair Data
    private String[] allTrackerUrls;
    private String[][][] allPairIdsAndTokenDetails; // Url -> [ pairId -> {paidID, token0Id, token1Id, token0Symbol, token1Symbol, decimal0, decimal1} ]
    private String walletPrivateKey, arbitrageContractAddress;

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
                    if (arbitrageSystem != null) {
                        arbitrageSystem.stopSystem();
                    }
                    MainClass.logPrintStream.println("Shutdown Handler Called...");
                    MainClass.logPrintStream.close();
                    MainClass.fileOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                System.out.println("Shutdown Successful...");
            }
        });

        initializeMongoSetup();

        Document document = new Document("identifier", "root");
        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
        assert foundDoc != null;
        thresholdPercentage = (String) foundDoc.get("thresholdPercentage");
        pollingInterval = (int) foundDoc.get("pollingInterval");
        List<?> list = (List<?>) foundDoc.get("admins");
        shouldRunBot = (boolean) foundDoc.get("shouldRunBot");
        for (Object item : list) {
            if (item instanceof String) {
                allAdmins.add((String) item);
            }
        }
        MainClass.logPrintStream.println("Admins : \n" + allAdmins + "\n\n");
        if (shouldRunBot && !startArbitrageSystem()) {
            sendMessage(allAdmins.get(0), "Error occurred when trying to start the bot. Fatal Error. Check it immediately");
        }
    }

    private void initializeMongoSetup() {
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
    }

    private void getInitializingDataFromMongoDB() {
        Document document = new Document("identifier", "root");
        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
        assert foundDoc != null;

        thresholdPercentage = (String) foundDoc.get("thresholdPercentage");
        walletPrivateKey = (String) foundDoc.get("walletPrivateKey");
        arbitrageContractAddress = (String) foundDoc.get("arbitrageContractAddress");
        maxPendingTrxAllowed = (int) foundDoc.get("maxPendingTrxAllowed");

        List<?> list1 = (List<?>) foundDoc.get("TrackerUrls");
        int len1 = list1.size();
        MainClass.logPrintStream.println("All Tracker Urls Size : " + len1);
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
    }

    public boolean startArbitrageSystem() {
        getInitializingDataFromMongoDB();

        if (!shouldRunBot) {
            shouldRunBot = true;
            Document document = new Document("identifier", "root");
            Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
            assert foundDoc != null;
            document = new Document("shouldRunBot", true);
            Bson updateOperation = new Document("$set", document);
            allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);
        }

        try {
            ArbitrageSystem arbitrageSystem = new ArbitrageSystem(this, arbitrageContractAddress, walletPrivateKey, pollingInterval,
                    maxPendingTrxAllowed, allTrackerUrls, allPairIdsAndTokenDetails); // TODO: Add values here...

            MainClass.logPrintStream.println("Call to Arbitrage Start System...");
            System.out.println("Call to Arbitrage Start System...");
            arbitrageSystem.startSystem();
            this.arbitrageSystem = arbitrageSystem;
            tempList.clear();
        } catch (IllegalArgumentException e) {
            e.printStackTrace(MainClass.logPrintStream);
            arbitrageSystem = null;
            stopArbitrageSystem();
            return false;
        }

        return true;
    }

    public void stopArbitrageSystem() {
        if (arbitrageSystem != null) {
            MainClass.logPrintStream.println("Call to Arbitrage Stop System Method");
            System.out.println("Call to Arbitrage Stop System Method");
            arbitrageSystem.stopSystem();
        }

        if (shouldRunBot) {
            shouldRunBot = false;
            Document document = new Document("identifier", "root");
            Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
            assert foundDoc != null;
            document = new Document("shouldRunBot", false);
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

    public void sendFile(String chatId, String fileName) {
        SendDocument sendDocument = new SendDocument();
        sendDocument.setChatId(chatId);
        sendDocument.setDocument(new InputFile().setMedia(new File(fileName)));
        sendDocument.setCaption(fileName);
        try {
            execute(sendDocument);
        } catch (Exception e) {
            e.printStackTrace(MainClass.logPrintStream);
        }
    }

    private void sendLogs(String chatId) {
        SendDocument sendDocument = new SendDocument();
        sendDocument.setChatId(chatId);
        MainClass.logPrintStream.flush();
        sendDocument.setDocument(new InputFile().setMedia(new File("OutputLogs.txt")));
        sendDocument.setCaption("Latest Logs");
        try {
            execute(sendDocument);
        } catch (Exception e) {
            sendMessage(chatId, "Error in sending Logs\n" + Arrays.toString(e.getStackTrace()));
            e.printStackTrace(MainClass.logPrintStream);
            e.printStackTrace();
        }
    }

    private void addNewPair(String chatId, String text) {
        String[] params = text.trim().split(" ");
        if (params.length == 3) {
            try {
                Set<String> oldPairs = arbitrageSystem.getAllUniSwapPairIds();
                ArrayList<String> result = arbitrageSystem.getPairDetails(params[1], params[2]);
                String token0Id = result.remove(0);
                String token1Id = result.remove(0);
                String token0Symbol = result.remove(0).toUpperCase();
                String token1Symbol = result.remove(0).toUpperCase();
                String decimal0 = result.remove(0);
                String decimal1 = result.remove(0);
                String msg = "Token0Id: " + token0Id + ", Token0Symbol: " + token0Symbol +
                        ", Token1Id: " + token1Id + ", Token1Symbol: " + token1Symbol +
                        ", Token0Decimals : " + decimal0 + "Token1Decimals" + decimal1 +
                        "\n\nAll PairIds:-\n" + result;

                String firstId = result.get(0);
                if (oldPairs.contains(firstId) || tempList.contains(firstId)) {
                    sendMessage(chatId, "This Pair Already Exist in the Monitoring List...");
                    return;
                }

                for (String host : allTrackerUrls) {
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
                            List<String> newData = new ArrayList<>();
                            newData.add(token0Id);
                            newData.add(token1Id);
                            newData.add(token0Symbol);
                            newData.add(token1Symbol);
                            newData.add(decimal0);
                            newData.add(decimal1);
                            document.append(newPairId, newData);

                            Bson updateOperation = new Document("$set", document);
                            allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);
                            tempList.add(newPairId);
                        } else if (host.equals(allTrackerUrls[0])) {
                            sendMessage(chatId, "This pair was not found on Uniswap. Any pair that you want to add needs to exist on " +
                                    "at least Uniswap. Other swaps are optional.");
                            return;
                        }
                    }
                }

                sendMessage(chatId, "Operation Successful. Following data was stored in the database:- \n\n" +
                        msg + "\n\n(Once you have added all new Pairs, please stop and restart the bot for changes to take effect.)");

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
                    "addNewPair token0Address token1Address");
        }
    }

    private void removeOldPair(String chatId, String text) {
        String[] params = text.trim().split(" ");
        if (params.length == 3) {
            String token0 = params[1].toLowerCase();
            String token1 = params[2].toLowerCase();

            if (token0.compareTo(token1) > 0) {
                String temp = token0;
                token0 = token1;
                token1 = temp;
            }

            ArrayList<String> pairIds = arbitrageSystem.removePair(token0, token1);
            if (pairIds != null) {
                int length = pairIds.size();
                for (int i = 0; i < length; i++) {
                    String id = pairIds.get(i);
                    if (!id.equalsIgnoreCase("")) {
                        String host = allTrackerUrls[i];
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

                        newDocument.remove(id);
                        Bson updateOperation = new Document("$set", newDocument);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);

                        foundDoc = allPairAndTrackersDataCollection.find(document).first();
                        assert foundDoc != null;
                        document = new Document("allPairIds", builtList);
                        updateOperation = new Document("$set", document);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);

                    }
                }
            } else {
                sendMessage(chatId, "No pair exist in with the given token addresses in the current monitoring list...");
            }
        } else {
            sendMessage(chatId, "Invalid Format. Correct Format: -\n" +
                    "removeOldPair token0Addy token1Addy");
        }
    }

    private void addNewTracker(String chatId, String text) {

        if (allTrackerUrls == null) {
            MainClass.logPrintStream.println("First Time Data Fetch from MongoDB ????");
            getInitializingDataFromMongoDB();
        }

        String[] params = text.trim().split(" ");
        if (params.length == 3) {
            try {
                Document document = new Document("trackerId", params[1]);
                Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
                if (foundDoc != null) {
                    throw new Exception("Url Already Exists");
                }
                TheGraphQueryMaker theGraphQueryMaker = new TheGraphQueryMaker(params[1], MainClass.logPrintStream);
                List<String> availablePairs = new ArrayList<>();

                try {
                    Document document1 = new Document("trackerId", allTrackerUrls[0]);
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
                    document.append("routerIndex", params[2]);
                    allPairAndTrackersDataCollection.insertOne(document);


                    try {
                        document = new Document("identifier", "root");
                        foundDoc = allPairAndTrackersDataCollection.find(document).first();
                        assert foundDoc != null;

                        List<?> list = (List<?>) foundDoc.get("TrackerUrls");
                        List<String> newList = new ArrayList<>();
                        int len1 = list.size();
                        allTrackerUrls = new String[len1];

                        for (Object item : list) {
                            if (item instanceof String) {
                                String currentUrl = (String) item;
                                newList.add(currentUrl);
                            }
                        }

                        newList.add(params[1]);
                        document = new Document("TrackerUrls", newList);
                        Bson updateOperation = new Document("$set", document);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);

                        sendMessage(chatId, "Operation Successful.\n(Please stop and restart the bot for changes to take effect.)");
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
        } else {
            sendMessage(chatId, "Wrong usage of command. Correct Format: -\n" +
                    "addNewTracker theGraphUrlEndpoint routerIndex");
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

    @SuppressWarnings("SpellCheckingInspection")
    @Override
    public void onUpdateReceived(Update update) {

        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();
            MainClass.logPrintStream.println("From : " + chatId + "\nIncoming Message :\n" + text + "\n\n");

            if (!allAdmins.contains(chatId)) {
                sendMessage(chatId, "This bot can only be used by authorized personnel. Sorry....");
                return;
            }

            if (text.equalsIgnoreCase("runBot")) {
                if (shouldRunBot) {
                    sendMessage(chatId, "The bot is already running...");
                } else {
                    try {
                        if (startArbitrageSystem()) {
                            sendMessage(chatId, "Operation Successful...");
                        } else {
                            throw new Exception("Unable to start the system");
                        }
                    } catch (Exception e) {
                        sendMessage(chatId, "There was an error while starting the bot... Please contact : @OreGaZembuTouchiSuru");
                        e.printStackTrace(MainClass.logPrintStream);
                    }
                }
            } else if (text.equalsIgnoreCase("stopBot")) {
                if (!shouldRunBot) {
                    sendMessage(chatId, "The bot is already stopped...");
                } else {
                    stopArbitrageSystem();
                    sendMessage(chatId, "Operation Successful...");
                }
            } else if (text.equalsIgnoreCase("restartBot")) {
                if (shouldRunBot) {
                    sendMessage(chatId, "Turning Off the bot now....");
                    stopArbitrageSystem();
                }
                try {
                    if (startArbitrageSystem()) {
                        sendMessage(chatId, "Bot turned on.\n\nOperation Successful");
                    } else {
                        throw new Exception("Unable to start the bot");
                    }
                } catch (Exception e) {
                    sendMessage(chatId, "There was an error while turning on the bot...");
                }
            } else if (text.toLowerCase().startsWith("setthresholdpercentage")) {
                String[] params = text.trim().split(" ");
                if (params.length == 2) {
                    try {
                        BigDecimal decimalValue = new BigDecimal(params[1]);
                        thresholdPercentage = params[1];
                        Document document = new Document("identifier", "root");
                        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
                        assert foundDoc != null;
                        document = new Document("thresholdPercentage", thresholdPercentage);
                        Bson updateOperation = new Document("$set", document);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);

//                        TODO : Need to change this to threshold levels...
//                        if (shouldRunBot) {
//                            arbitrageSystem.thresholdProfitAmount = decimalValue;
//                        }

                    } catch (NumberFormatException e) {
                        sendMessage(chatId, "Invalid Format... The decimalValue has to be an integer or a decimal between 0 and 100");
                    }
                } else {
                    sendMessage(chatId, "Wrong Usage of Command. Correct Format : \n" +
                            "setThresholdPercentage decimalValue");
                }
            } else if (text.toLowerCase().startsWith("setpollinginterval")) {
                String[] params = text.trim().split(" ");
                if (params.length == 2) {
                    Document document = new Document("identifier", "root");
                    Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
                    assert foundDoc != null;

                    try {
                        int interval = Integer.parseInt(params[1]);
                        if (interval < 7500) {
                            sendMessage(chatId, "timeInMillis has to be larger than 7500");
                            return;
                        }
                        document = new Document("pollingInterval", interval);
                        Bson updateOperation = new Document("$set", document);
                        allPairAndTrackersDataCollection.updateOne(foundDoc, updateOperation);
                        pollingInterval = interval;
                        if (shouldRunBot) {
                            arbitrageSystem.waitTimeInMillis = interval;
                        }
                    } catch (NumberFormatException e) {
                        sendMessage(chatId, "Invalid time. timeInMillis has to be an Integer.");
                    }
                } else {
                    sendMessage(chatId, "Invalid Format. Correct Format: -\n" +
                            "setPollingInterval timeInMillis");
                }
            } else if (text.toLowerCase().startsWith("addnewpair")) {
                if (shouldRunBot) {
                    addNewPair(chatId, text);
                } else {
                    sendMessage(chatId, "This command can only be used when the bot is running...");
                }
            } else if (text.toLowerCase().startsWith("removeoldpair")) {
                if (shouldRunBot) {
                    removeOldPair(chatId, text);
                } else {
                    sendMessage(chatId, "This command can only be used when the bot is running...");
                }
            } else if (text.toLowerCase().startsWith("addnewtrackerurl")) {
                addNewTracker(chatId, text);
            } else if (text.equalsIgnoreCase("getAllPairDetails")) {
                if (!shouldRunBot) {
                    sendMessage(chatId, "This command can only be used when the bot is running...");
                } else {
                    if (!arbitrageSystem.printAllDeterminedData(chatId)) {
                        sendMessage(chatId, "Error while generating data...");
                    }
                }
            } else if (text.toLowerCase().startsWith("setWalletPrivateKey".toLowerCase())) {
                String[] params = text.trim().split(" ");
                if (params.length == 2) {
                    try {
                        Credentials credentials = Credentials.create(params[1]);
                        Document document = new Document("identifier", "root");
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
                            "setWalletPrivateKey privateKey");
                }
            } else if (text.equalsIgnoreCase("getLogs")) {
                sendLogs(chatId);
            } else if (text.equalsIgnoreCase("clearLogs")) {
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
            } else if (text.equalsIgnoreCase("Commands")) {
                sendMessage(chatId, """
                        runBot
                        stopBot
                        restartBot
                        setThresholdPercentage decimalValue
                        addNewPair token0Addy token1Addy
                        removeOldPair token0Addy token1Addy
                        addNewTrackerUrl theGraphUrl
                        getAllPairDetails
                        setWalletPrivateKey privateKey
                        getLogs
                        clearLogs
                        Commands
                                                
                                                
                        (For any command, 1st word is the actual command name and it may be followed by 0 or more parameters that must be replaced by the actual values.)""");
            } else {
                sendMessage(chatId, "Such command does not exists. (ノಠ益ಠ)ノ彡┻━┻");
            }

            sendMessage(chatId, "Is Bot Running : " + shouldRunBot + "\nThreshold Percentage : " + thresholdPercentage + "%");
        }
    }
}
