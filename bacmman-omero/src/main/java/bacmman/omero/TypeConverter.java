package bacmman.omero;

import omero.*;

public class TypeConverter {
    public static Object convert(RType object) {
        if (object instanceof RString) {
            return ((RString)object).getValue();
        } else if (object instanceof RLong) {
            return ((RLong)object).getValue();
        } else if (object instanceof RInt) {
            return ((RInt)object).getValue();
        } else if (object instanceof RDouble) {
            return ((RDouble)object).getValue();
        } else if (object instanceof RFloat) {
            return ((RFloat)object).getValue();
        } else if (object instanceof RBool) {
            return ((RBool)object).getValue();
        } else {
            return null;
        }
    }
}
