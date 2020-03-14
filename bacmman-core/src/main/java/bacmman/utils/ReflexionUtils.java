package bacmman.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class ReflexionUtils {
    static Logger logger = LoggerFactory.getLogger(ReflexionUtils.class);
    public static void setvalueOnFinalField(Object object, String fieldName, Object newValue)  {
        Field field = null;
        try {
            field = object.getClass().getDeclaredField( fieldName );
            field.setAccessible(true);

            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

            field.set(object, newValue);
        } catch (NoSuchFieldException|IllegalAccessException e) {
            logger.debug("cannot set value", e);
        }


    }
}
