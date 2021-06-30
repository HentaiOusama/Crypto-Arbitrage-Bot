import org.apache.log4j.BasicConfigurator;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

        BasicConfigurator.configure();
        disableAccessWarnings();
        System.setProperty("com.google.inject.internal.cglib.$experimental_asm7", "true");

        // Starting Telegram bot and Web3 services
        try {
            TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
            telegramBotsApi.registerBot(new ArbitrageTelegramBot());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void disableAccessWarnings() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);

            Method putObjectVolatile = unsafeClass.getDeclaredMethod("putObjectVolatile", Object.class, long.class, Object.class);
            Method staticFieldOffset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field.class);

            Class loggerClass = Class.forName("jdk.internal.module.IllegalAccessLogger");
            Field loggerField = loggerClass.getDeclaredField("logger");
            Long offset = (Long) staticFieldOffset.invoke(unsafe, loggerField);
            putObjectVolatile.invoke(unsafe, loggerClass, offset, null);
        } catch (Exception ignored) {
        }
    }
}
