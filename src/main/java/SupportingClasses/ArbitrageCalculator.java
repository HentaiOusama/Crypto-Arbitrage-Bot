package SupportingClasses;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public class ArbitrageCalculator {

    // Calculation Constants
    private static final BigDecimal Z_p_997 = BigDecimal.valueOf(0.997);
    private static final BigDecimal Z_p_997_sq = Z_p_997.pow(2);
    private static final BigDecimal One_m_Z_p_997_sq = BigDecimal.ONE.subtract(Z_p_997_sq);
    private static final BigDecimal val0 = BigDecimal.ZERO;
    private static final MathContext mathContextDown = new MathContext(20, RoundingMode.HALF_DOWN),
            mathContextEven = new MathContext(20, RoundingMode.HALF_EVEN);

    public static BigDecimal[] getMaximumPossibleProfitAndBorrowAmount(final BigDecimal volumeOfT0OnExA, final BigDecimal volumeOfT1OnExA,
                                                                       final BigDecimal volumeOfT0OnExB, final BigDecimal volumeOfT1OnExB) {

        /*
         * The calculations are based on the following procedure: -
         * 1) We borrow B1 amount of coin 1 from Ex. A
         * 2) Sell those B1 amount coin 1 on Ex. B
         * 3) We receive S2 amount of coin 2 from Ex. B
         * 4) We return R2 amount of coin 2 to Ex. A
         * 5) Profit = S2 - R2
         *
         * But, this procedure cannot be actually used for flash loans.
         * */

        /*
         * Holders (Initialized to 0)
         * Index 0 => Profit
         * Index 1 => Borrow Amount
         * */
        BigDecimal[] result = new BigDecimal[]{val0, val0};

        // Step 1
        if (volumeOfT0OnExA.compareTo(val0) <= 0 || volumeOfT0OnExB.compareTo(val0) <= 0
                || volumeOfT1OnExA.compareTo(val0) <= 0 || volumeOfT1OnExB.compareTo(val0) <= 0) {
            return result;
        } else if ((volumeOfT1OnExA.divide(volumeOfT0OnExA, mathContextDown))
                .compareTo(volumeOfT1OnExB.divide(volumeOfT0OnExB, mathContextDown)) >= 0) {
            return result;
        }

        // Step 2
        BigDecimal _0B_X_1B_ = volumeOfT0OnExB.multiply(volumeOfT1OnExB);
        BigDecimal _0A_X_1A_ = volumeOfT0OnExA.multiply(volumeOfT1OnExA);
        BigDecimal denominator = _0B_X_1B_.subtract(_0A_X_1A_);
        denominator = Z_p_997.multiply(denominator);

        // Pre-calculations
        boolean singleBorrowMode = true;
        BigDecimal B11 = null, B12 = null;

        // if Step 3 else (Step 4, Step 5 & Step 6)
        if (denominator.compareTo(val0) == 0) {
            BigDecimal B1 = (volumeOfT0OnExA.multiply(volumeOfT1OnExB).multiply(Z_p_997_sq))
                    .subtract(volumeOfT0OnExB.multiply(volumeOfT1OnExA));
            BigDecimal den = (Z_p_997_sq.multiply(volumeOfT1OnExA)).add(Z_p_997.multiply(volumeOfT1OnExB));
            den = den.multiply(BigDecimal.valueOf(2));
            B1 = B1.divide(den, mathContextEven);

            if ((B1.compareTo(val0) <= 0) || (B1.compareTo(volumeOfT0OnExA) >= 0)) {
                return result;
            } else {
                result[1] = B1;
            }
        } else {
            BigDecimal numeratorLeft = ((Z_p_997.multiply(volumeOfT1OnExA)).add(volumeOfT1OnExB))
                    .multiply(volumeOfT0OnExA.multiply(volumeOfT0OnExB));

            BigDecimal discriminant = ((Z_p_997.multiply(volumeOfT0OnExA)).add(volumeOfT0OnExB)).pow(2);
            discriminant = discriminant.multiply(_0A_X_1A_).multiply(_0B_X_1B_);
            BigDecimal temp = (volumeOfT0OnExA.multiply(volumeOfT0OnExB)).pow(2);
            temp = temp.multiply((volumeOfT1OnExB.pow(2)).subtract(volumeOfT1OnExA.pow(2)));
            temp = temp.multiply(One_m_Z_p_997_sq);
            discriminant = (discriminant.add(temp)).sqrt(mathContextEven);

            // Complex roots => Return 0
            if (discriminant.compareTo(val0) < 0) {
                return result;
            }

            B11 = numeratorLeft.add(discriminant);
            B12 = numeratorLeft.subtract(discriminant);
            B11 = B11.divide(denominator, mathContextEven);
            B12 = B12.divide(denominator, mathContextEven);
            boolean a = (B11.compareTo(val0) > 0) && (B11.compareTo(volumeOfT0OnExA) < 0),
                    b = (B12.compareTo(val0) > 0) && (B12.compareTo(volumeOfT0OnExA) < 0);

            if (a && b) {
                singleBorrowMode = false;
            } else if (a) {
                result[1] = B11;
            } else if (b) {
                result[1] = B12;
            } else {
                return result;
            }
        }

        // Step 6
        if (singleBorrowMode) {
            BigDecimal P = calculateProfit(volumeOfT0OnExA, volumeOfT1OnExA, volumeOfT0OnExB, volumeOfT1OnExB, result[1]);

            if ((P.compareTo(val0) > 0) && (P.compareTo(volumeOfT1OnExB) < 0)) {
                result[0] = P;
            } else {
                result[1] = val0;
            }
        } else {
            BigDecimal P1 = calculateProfit(volumeOfT0OnExA, volumeOfT1OnExA, volumeOfT0OnExB, volumeOfT1OnExB, B11);
            BigDecimal P2 = calculateProfit(volumeOfT0OnExA, volumeOfT1OnExA, volumeOfT0OnExB, volumeOfT1OnExB, B12);

            if (P1.compareTo(val0) > 0 && P1.compareTo(volumeOfT1OnExB) < 0) {
                result[0] = P1;
                result[1] = B11;
            }

            if (P2.compareTo(val0) > 0 && P2.compareTo(volumeOfT1OnExB) < 0 && P2.compareTo(result[0]) > 0) {
                result[0] = P2;
                result[1] = B11;
            }
        }

        return result;
    }

    private static BigDecimal calculateProfit(final BigDecimal volumeOfT0OnExA, final BigDecimal volumeOfT1OnExA, final BigDecimal volumeOfT0OnExB,
                                              final BigDecimal volumeOfT1OnExB, final BigDecimal borrowAmount) {
        BigDecimal leftTerm = Z_p_997.multiply(volumeOfT1OnExB).multiply(borrowAmount);
        BigDecimal temp = volumeOfT0OnExB.add(Z_p_997.multiply(borrowAmount));
        leftTerm = leftTerm.divide(temp, mathContextEven);

        BigDecimal rightTerm = volumeOfT1OnExA.multiply(borrowAmount);
        temp = Z_p_997.multiply(volumeOfT0OnExA.subtract(borrowAmount));
        rightTerm = rightTerm.divide(temp, mathContextEven);

        return leftTerm.subtract(rightTerm);
    }
}
