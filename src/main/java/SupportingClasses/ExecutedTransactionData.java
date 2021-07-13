package SupportingClasses;

import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

public class ExecutedTransactionData {
    public final String trxHash, encodedFunction;
    MathContext mathContext = new MathContext(20, RoundingMode.HALF_EVEN);
    public final BigDecimal gasPrice;
    private final BigDecimal decimals;
    private final String repayTokenSymbol;
    private final BigDecimal thresholdEthAmount, borrowAmount, expectedProfitInEth;

    public ExecutedTransactionData(String trxHash, String encodedFunction, BigInteger gasPrice, String repayTokenSymbol, int decimals,
                                   BigDecimal thresholdEthAmount, BigDecimal borrowAmount, BigDecimal expectedProfitInEth) {
        this.trxHash = trxHash;
        this.encodedFunction = encodedFunction;
        this.gasPrice = new BigDecimal(gasPrice, mathContext);
        this.repayTokenSymbol = repayTokenSymbol;
        this.thresholdEthAmount = thresholdEthAmount;
        this.borrowAmount = borrowAmount;
        this.expectedProfitInEth = expectedProfitInEth;
        this.decimals = new BigDecimal(BigInteger.TEN.pow(decimals), mathContext);
    }

    @SuppressWarnings("SpellCheckingInspection")
    public String getPrintableData(TransactionReceipt transactionReceipt) {
        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append(trxHash)
                .append(",-")
                .append(new BigDecimal(transactionReceipt.getGasUsed()).multiply(gasPrice).divide(
                        new BigDecimal("1000000000000000000"), mathContext
                ))
                .append(" ETH,");

        if (transactionReceipt.isStatusOK()) {
            List<Log> logs = transactionReceipt.getLogs();
            for (Log log : logs) {
                if (log.getTopics().get(0).equalsIgnoreCase("0xcb3b7311e1762fa145efce6ad1e9a8c6a046723e9cab70d3a0753bba5326edd3")) {
                    stringBuilder.append("+")
                            .append(new BigDecimal(Numeric.toBigInt(log.getData())).divide(decimals, mathContext))
                            .append(" ")
                            .append(repayTokenSymbol);
                    break;
                }
            }
        } else {
            stringBuilder.append("0")
                    .append(" ")
                    .append(repayTokenSymbol);
        }

        stringBuilder.append(",")
                .append(thresholdEthAmount)
                .append(",")
                .append(borrowAmount)
                .append(",")
                .append(expectedProfitInEth);

        return stringBuilder.toString();
    }
}
