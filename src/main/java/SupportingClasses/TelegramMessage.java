package SupportingClasses;

import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

public class TelegramMessage {
    public SendMessage sendMessage;
    public SendAnimation sendAnimation;
    public boolean isMessage, hasTransactionData;

    public TelegramMessage(SendMessage sendMessage) {
        this.sendMessage = sendMessage;
        isMessage = true;
        hasTransactionData = false;
    }

    public TelegramMessage(SendAnimation sendAnimation) {
        this.sendAnimation = sendAnimation;
        isMessage = false;
        hasTransactionData = false;
    }
}