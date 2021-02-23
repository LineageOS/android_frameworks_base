package motorola.hardware.health.V1_0;


public final class PowerSupplyModType {
    public static final int POWER_SUPPLY_MOD_TYPE_UNKNOWN = 0;
    public static final int POWER_SUPPLY_MOD_TYPE_REMOTE = 1;
    public static final int POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL = 2;
    public static final int POWER_SUPPLY_MOD_TYPE_EMERGENCY = 3;
    public static final String toString(int o) {
        if (o == POWER_SUPPLY_MOD_TYPE_UNKNOWN) {
            return "POWER_SUPPLY_MOD_TYPE_UNKNOWN";
        }
        if (o == POWER_SUPPLY_MOD_TYPE_REMOTE) {
            return "POWER_SUPPLY_MOD_TYPE_REMOTE";
        }
        if (o == POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL) {
            return "POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL";
        }
        if (o == POWER_SUPPLY_MOD_TYPE_EMERGENCY) {
            return "POWER_SUPPLY_MOD_TYPE_EMERGENCY";
        }
        return "0x" + Integer.toHexString(o);
    }

    public static final String dumpBitfield(int o) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        int flipped = 0;
        list.add("POWER_SUPPLY_MOD_TYPE_UNKNOWN"); // POWER_SUPPLY_MOD_TYPE_UNKNOWN == 0
        if ((o & POWER_SUPPLY_MOD_TYPE_REMOTE) == POWER_SUPPLY_MOD_TYPE_REMOTE) {
            list.add("POWER_SUPPLY_MOD_TYPE_REMOTE");
            flipped |= POWER_SUPPLY_MOD_TYPE_REMOTE;
        }
        if ((o & POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL) == POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL) {
            list.add("POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL");
            flipped |= POWER_SUPPLY_MOD_TYPE_SUPPLEMENTAL;
        }
        if ((o & POWER_SUPPLY_MOD_TYPE_EMERGENCY) == POWER_SUPPLY_MOD_TYPE_EMERGENCY) {
            list.add("POWER_SUPPLY_MOD_TYPE_EMERGENCY");
            flipped |= POWER_SUPPLY_MOD_TYPE_EMERGENCY;
        }
        if (o != flipped) {
            list.add("0x" + Integer.toHexString(o & (~flipped)));
        }
        return String.join(" | ", list);
    }

};

