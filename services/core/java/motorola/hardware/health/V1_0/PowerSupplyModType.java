package motorola.hardware.health.V1_0;

import java.util.ArrayList;

public final class PowerSupplyModType {
    public static final int POWER_SUPPLY_MOD_TYPE_EMERGENCY = 3;
    public static final int POWER_SUPPLY_MOD_TYPE_REMOTE = 1;
    public static final int POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL = 2;
    public static final int POWER_SUPPLY_MOD_TYPE_UNKNOWN = 0;

    public static final String toString(int o) {
        if (o == 0) {
            return "POWER_SUPPLY_MOD_TYPE_UNKNOWN";
        }
        if (o == 1) {
            return "POWER_SUPPLY_MOD_TYPE_REMOTE";
        }
        if (o == 2) {
            return "POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL";
        }
        if (o == 3) {
            return "POWER_SUPPLY_MOD_TYPE_EMERGENCY";
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("0x");
        stringBuilder.append(Integer.toHexString(o));
        return stringBuilder.toString();
    }

    public static final String dumpBitfield(int o) {
        ArrayList<String> list = new ArrayList();
        int flipped = 0;
        list.add("POWER_SUPPLY_MOD_TYPE_UNKNOWN");
        if ((o & 1) == 1) {
            list.add("POWER_SUPPLY_MOD_TYPE_REMOTE");
            flipped = 0 | 1;
        }
        if ((o & 2) == 2) {
            list.add("POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL");
            flipped |= 2;
        }
        if ((o & 3) == 3) {
            list.add("POWER_SUPPLY_MOD_TYPE_EMERGENCY");
            flipped |= 3;
        }
        if (o != flipped) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("0x");
            stringBuilder.append(Integer.toHexString((~flipped) & o));
            list.add(stringBuilder.toString());
        }
        return String.join(" | ", list);
    }
}
