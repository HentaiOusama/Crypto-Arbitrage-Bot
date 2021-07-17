package SupportingClasses;

public class TwoDexPairData {
    public String key, token0, token1, parentPairId, childPairId, symbol0, symbol1, decimal0, decimal1, parentDex, childDex;

    public String setAndGetKey() {
        key = token0 + ", " + token1;
        return key;
    }

    public boolean isValid() {
        boolean flag = key != null && !key.equalsIgnoreCase("");
        flag = flag && token0 != null && !token0.equalsIgnoreCase("");
        flag = flag && token1 != null && !token1.equalsIgnoreCase("");
        flag = flag && parentPairId != null && !parentPairId.equalsIgnoreCase("");
        flag = flag && childPairId != null && !childPairId.equalsIgnoreCase("");
        flag = flag && parentDex != null && !parentDex.equalsIgnoreCase("");
        flag = flag && childDex != null && !childDex.equalsIgnoreCase("");

        return flag;
    }
}
