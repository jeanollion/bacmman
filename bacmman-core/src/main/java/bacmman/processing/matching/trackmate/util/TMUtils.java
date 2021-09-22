package bacmman.processing.matching.trackmate.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class TMUtils {


    /**
     * Check that the given map has all some keys. Two String collection allows
     * specifying that some keys are mandatory, other are optional.
     *
     * @param map
     *            the map to inspect.
     * @param mandatoryKeys
     *            the collection of keys that are expected to be in the map. Can
     *            be <code>null</code>.
     * @param optionalKeys
     *            the collection of keys that can be - or not - in the map. Can
     *            be <code>null</code>.
     * @param errorHolder
     *            will be appended with an error message.
     * @return if all mandatory keys are found in the map, and possibly some
     *         optional ones, but no others.
     */
    public static final < T > boolean checkMapKeys(final Map< T, ? > map, Collection< T > mandatoryKeys, Collection< T > optionalKeys, final StringBuilder errorHolder )
    {
        if ( null == optionalKeys )
        {
            optionalKeys = new ArrayList< >();
        }
        if ( null == mandatoryKeys )
        {
            mandatoryKeys = new ArrayList< >();
        }
        boolean ok = true;
        final Set< T > keySet = map.keySet();
        for ( final T key : keySet )
        {
            if ( !( mandatoryKeys.contains( key ) || optionalKeys.contains( key ) ) )
            {
                ok = false;
                errorHolder.append( "Map contains unexpected key: " + key + ".\n" );
            }
        }

        for ( final T key : mandatoryKeys )
        {
            if ( !keySet.contains( key ) )
            {
                ok = false;
                errorHolder.append( "Mandatory key " + key + " was not found in the map.\n" );
            }
        }
        return ok;

    }


    /**
     * Check the presence and the validity of a key in a map, and test it is of
     * the desired class.
     *
     * @param map
     *            the map to inspect.
     * @param key
     *            the key to find.
     * @param expectedClass
     *            the expected class of the target value .
     * @param errorHolder
     *            will be appended with an error message.
     * @return true if the key is found in the map, and map a value of the
     *         desired class.
     */
    public static final boolean checkParameter( final Map< String, Object > map, final String key, final Class< ? > expectedClass, final StringBuilder errorHolder )
    {
        final Object obj = map.get( key );
        if ( null == obj )
        {
            errorHolder.append( "Parameter " + key + " could not be found in settings map, or is null.\n" );
            return false;
        }
        if ( !expectedClass.isInstance( obj ) )
        {
            errorHolder.append( "Value for parameter " + key + " is not of the right class. Expected " + expectedClass.getName() + ", got " + obj.getClass().getName() + ".\n" );
            return false;
        }
        return true;
    }

}
