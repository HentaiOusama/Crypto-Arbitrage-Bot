import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.File;
import java.util.List;

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

    // MongoDB Related Stuff
    private ClientSession clientSession;
    private MongoCollection<Document> allPairAndTrackersDataCollection;

    // Tracker and Pair Data
    private String[] allTrackerUrls;
    private String[][][] allPairIdsAndTokenDetails; // url -> [ pairId -> {paidID, token0Id, token1Id, token0Symbol, token1Symbol} ]

    ArbitrageTelegramBot() {

        initializeMongoSetup();

        Document document = new Document("identifier", "root");
        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
        assert foundDoc != null;
        shouldRunBot = (boolean) foundDoc.get("shouldRunBot");
        if (shouldRunBot) {
            startArbitrageSystem();
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
        MongoClient mongoClient = MongoClients.create(mongoClientSettings);
        clientSession = mongoClient.startSession();
        allPairAndTrackersDataCollection = mongoClient.getDatabase("Arbitrage-Bot-Database").getCollection("All-Pairs-And-Trackers-Data");
    }

    private void getInitializingDataFromMongoDB() {
        Document document = new Document("identifier", "root");
        Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
        assert foundDoc != null;
        List<?> list1 = (List<?>) foundDoc.get("TrackerUrls");
        int len1 = list1.size();
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
                    allPairIdsAndTokenDetails = new String[len1][list2.size()][5];
                }

                int len2 = list2.size();
                for (int j = 0; j < len2; j++) {
                    Object item2 = list2.get(j);
                    if (item2 instanceof String) {
                        String currentPairId = (String) item2;
                        List<?> list3 = (List<?>) foundDoc.get(currentPairId);

                        int len3 = list3.size();
                        allPairIdsAndTokenDetails[i][j][0] = currentPairId;
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

    public void startArbitrageSystem() {
        getInitializingDataFromMongoDB();

        if (!shouldRunBot) {
            Document document = new Document("identifier", "root");
            Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
            assert foundDoc != null;
            shouldRunBot = true;
            document = new Document("shouldRunBot", true);
            Bson updateAddyDocOperation = new Document("$set", document);
            allPairAndTrackersDataCollection.updateOne(foundDoc, updateAddyDocOperation);
        }

        ArbitrageSystem arbitrageSystem = new ArbitrageSystem(this, "", 10000,
                0, allTrackerUrls, allPairIdsAndTokenDetails);

        MainClass.logPrintStream.println("Call to Arbitrage Run Method");
        System.out.println("Call to Arbitrage Run Method");
        Thread t = new Thread(arbitrageSystem);
        t.start();
        this.arbitrageSystem = arbitrageSystem;
    }

    public void stopArbitrageSystem() {
        MainClass.logPrintStream.println("Call to Arbitrage Stop System Method");
        System.out.println("Call to Arbitrage Stop System Method");
        arbitrageSystem.stopSystem();

        if (shouldRunBot) {
            Document document = new Document("identifier", "root");
            Document foundDoc = allPairAndTrackersDataCollection.find(document).first();
            assert foundDoc != null;
            shouldRunBot = false;
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

        // Need to add the authorized Check Using: update.getMessage().getChatId()
        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();
            MainClass.logPrintStream.println("Incoming Message :\n" + text);

            if (text.equalsIgnoreCase("runBot")) {
                if (shouldRunBot) {
                    sendMessage(chatId, "The bot is already running...");
                } else {
                    startArbitrageSystem();
                    sendMessage(chatId, "Operation Successful...");
                }
            } else if (text.equalsIgnoreCase("stopBot")) {
                if (!shouldRunBot) {
                    sendMessage(chatId, "The bot is already stopped...");
                } else {
                    stopArbitrageSystem();
                    sendMessage(chatId, "Operation Successful...");
                }
            } else if (text.equalsIgnoreCase("Commands")) {
                sendMessage(chatId, """
                        runBot
                        stopBot
                        Commands
                        (amount has to be bigInteger including 18 decimal eth precision)""");
            } else {
                sendMessage(chatId, "Such command does not exists. BaaaaaaaaaKa");
            }
        }
    }
}
