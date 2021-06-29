package SupportingClasses;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class AnalysedPairData implements Comparable<AnalysedPairData> {

    public final String pairKeyForMapper;

    public BigDecimal minPrice, maxPrice, priceDifference;
    public int minIndex, maxIndex;
    public final float priceDifferencePercentage;

    public AnalysedPairData(String pairKeyForMapper, BigDecimal minPrice, BigDecimal maxPrice, int minIndex, int maxIndex) {
        this.pairKeyForMapper = pairKeyForMapper;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.minIndex = minIndex;
        this.maxIndex = maxIndex;
        priceDifference = maxPrice.subtract(minPrice);
        priceDifferencePercentage = priceDifference.multiply(BigDecimal.valueOf(100)).divide(maxPrice, RoundingMode.HALF_DOWN).floatValue();
    }

    @Override
    public int compareTo(@NotNull AnalysedPairData o) {
        return priceDifference.compareTo(o.priceDifference);
    }

    @Override
    public String toString() {
        return "PairKeyForMapper: (" + pairKeyForMapper + "), minPrice: " + minPrice + ", maxPrice: " + maxPrice +
                ", priceDifference: " + priceDifference + ", priceDifferencePercentage: " + priceDifferencePercentage;
    }
}
