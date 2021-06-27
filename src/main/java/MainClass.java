import java.io.FileOutputStream;
import java.io.PrintStream;

public class MainClass {

    public static FileOutputStream fileOutputStream;
    public static PrintStream logPrintStream;

    public static void main(String[] args) {

        // Setting the logger
        try {
            fileOutputStream = new FileOutputStream("OutputLogs.txt");
            logPrintStream = new PrintStream(fileOutputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }

        ArbitrageSystem arbitrageSystem = new ArbitrageSystem("", 7500,
                new String[]{"https://api.thegraph.com/subgraphs/name/uniswap/uniswap-v2", "https://thegraph.com/explorer/subgraph/sushiswap/exchange"});

        logPrintStream.println("Call to Arbitrage Run Method");
        System.out.println("Call to Arbitrage Run Method");
        Thread t = new Thread(arbitrageSystem);
        t.start();
        try {
            Thread.sleep(2500);
        } catch (Exception e) {
            e.printStackTrace(logPrintStream);
        }
        logPrintStream.println("Call to Arbitrage Stop System Method");
        System.out.println("Call to Arbitrage Stop System Method");
        arbitrageSystem.stopSystem();
    }
}
