import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.util.List;

/*
 * Requirements: -
 * 1) Environment Var: mongoID - MongoDB ID
 * 2) Environment Var: mongoPass - MongoDB Password
 * */
public class ArbitrageTelegramBot {

    // MongoDB Related Stuff
    private final String botName = "Arbitrage Bot";
    private ClientSession clientSession;
    private MongoCollection<Document> allPairAndTrackersDataCollection;

    // Tracker and Pair Data
    private String[] allTrackerUrls;
    private String[][][] allPairIdsAndTokenDetails; // url -> [ pairId -> {paidID, token0Id, token1Id, token0Symbol, token1Symbol} ]

    ArbitrageTelegramBot() {

        initializeMongoSetup();

        startArbitrageSystem();
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

        ArbitrageSystem arbitrageSystem = new ArbitrageSystem("", 10000, 0,
                allTrackerUrls, allPairIdsAndTokenDetails);

        MainClass.logPrintStream.println("Call to Arbitrage Run Method");
        System.out.println("Call to Arbitrage Run Method");
        Thread t = new Thread(arbitrageSystem);
        t.start();
        try {
            Thread.sleep(2500);
        } catch (Exception e) {
            e.printStackTrace(MainClass.logPrintStream);
        }
        MainClass.logPrintStream.println("Call to Arbitrage Stop System Method");
        System.out.println("Call to Arbitrage Stop System Method");
        arbitrageSystem.stopSystem();
    }

}
