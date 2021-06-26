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

        ArbitrageSystem arbitrageSystem = new ArbitrageSystem("");
        arbitrageSystem.run();
        try {
            Thread.sleep(2500);
        } catch (Exception e) {
            e.printStackTrace(logPrintStream);
        }
        arbitrageSystem.stopSystem();
    }
}
